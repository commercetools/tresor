use std::{collections::HashMap, path::PathBuf};

use chrono::Utc;
use clap::{command, Args, Parser, Subcommand};
use config::{config_file_path, get_env, load_or_create_config, Config, EnvironmentConfig};
use error::CliError;
use vaultrs::api::kv2::requests::SetSecretMetadataRequestBuilder;

mod config;
mod error;
mod vault;

#[derive(Parser, Debug)]
#[command(version, about, long_about = None)]
struct TresorArgs {
    /// vault environment to use: staging or production
    #[clap(short, long, env = "VAULT_ENVIRONMENT")]
    environment: String,

    #[command(subcommand)]
    command: Commands,
}

#[derive(Debug, Args)]
struct VaultCommandArgs {
    /// path to use
    #[clap(short, long, env = "VAULT_PATH")]
    path: Option<String>,
    /// context to use
    #[clap(short, long, env = "VAULT_CONTEXT")]
    context: String,
    /// service to use in template
    #[clap(short, long, env = "VAULT_SERVICE")]
    service: Option<String>,
}

#[derive(Subcommand, Debug)]
enum Commands {
    Login {
        role: Option<String>,
    },
    List(VaultCommandArgs),
    Get(VaultCommandArgs),
    Set(SetCommandArgs),
    ShowConfig,
    /// print the current token of the environment
    Token,
}

#[derive(Debug, Args)]
struct SetCommandArgs {
    input_file: PathBuf,

    #[command(flatten)]
    vault: VaultCommandArgs,

    #[clap(long, env = "VAULT_METADATA_OWNER")]
    metadata_owner: Option<String>,
    #[clap(long, env = "VAULT_METADATA_MAX_TTL")]
    metadata_max_ttl: Option<String>,
    #[clap(long, env = "VAULT_METADATA_ROTATION")]
    metadata_rotation: Option<bool>,
}

fn replace_variables(
    value: &str,
    replacements: &HashMap<String, String>,
) -> Result<String, CliError> {
    let mut replaced = value.to_owned();
    for (key, value) in replacements {
        replaced = replaced.replace(&format!("{{{{{}}}}}", key), &value);
    }
    Ok(replaced)
}

fn mount_and_path(
    config: &Config,
    env: &EnvironmentConfig,
    args: &VaultCommandArgs,
) -> Result<(String, String), CliError> {
    let context = env
        .contexts
        .clone()
        .into_iter()
        .find_map(|context| {
            let context_lowercase = context.to_string().to_lowercase();
            if context_lowercase == args.context {
                Some(context)
            } else if context_lowercase.contains(args.context.as_str()) {
                println!(
                    "found context '{}' via partial match of '{}'",
                    context, args.context
                );
                Some(context)
            } else {
                None
            }
        })
        .ok_or(CliError::RuntimeError(format!(
            "context {} not found in environment {}, must be part of:\n{}",
            args.context,
            env.name,
            env.contexts.join("\n")
        )))?;

    let mut replacement_values: HashMap<String, String> = HashMap::new();
    replacement_values.insert("context".into(), context);
    replacement_values.insert("environment".into(), env.name.clone());
    replacement_values.insert(
        "service".into(),
        args.service.clone().unwrap_or("default".into()),
    );

    let mount = replace_variables(
        &config.mount_template.clone().unwrap_or("".into()),
        &replacement_values,
    )?;

    let path_base = replace_variables(
        &config.path_template.clone().unwrap_or("".into()),
        &replacement_values,
    )?;

    let path: String = format!("{path_base}{}", args.path.clone().unwrap_or("".into()));
    Ok((mount, path))
}

#[tokio::main]
async fn main() -> Result<(), CliError> {
    let args = &TresorArgs::parse();
    let environment = &args.environment;
    let config = load_or_create_config().await?;

    match &args.command {
        Commands::Login { role } => {
            vault::login(&config, environment, role.to_owned()).await?;
            Ok(())
        }
        Commands::Token => {
            let env = get_env(&config, environment).await?;
            let token = env.valid_token()?;
            println!("export VAULT_TOKEN={}", token);
            println!("export VAULT_ADDR={}", env.vault_address);
            println!("export VAULT_ADDRESS={}", env.vault_address);
            Ok(())
        }
        Commands::ShowConfig => {
            let config_file = config_file_path().await?;
            println!(
                "config {}:{}",
                config_file.display(),
                serde_json::to_string_pretty(&config)?
            );
            Ok(())
        }
        Commands::List(args) => {
            let env = get_env(&config, environment).await?;
            let (mount, path) = mount_and_path(&config, &env, args)?;

            println!("listing secrets in {mount}/{path}:");

            let list = vaultrs::kv2::list(&env.vault_client()?, &mount, &path).await?;

            for entry in list {
                println!("{}", entry)
            }

            Ok(())
        }
        Commands::Get(args) => {
            let env = get_env(&config, environment).await?;
            let (mount, path) = mount_and_path(&config, &env, args)?;

            println!("{mount}/{path}:");

            let value =
                vaultrs::kv2::read::<serde_json::Value>(&env.vault_client()?, &mount, &path)
                    .await?;

            println!("{}", serde_json::to_string_pretty(&value)?);

            let metadata = vaultrs::kv2::read_metadata(&env.vault_client()?, &mount, &path).await?;
            println!("metadata:\n{}", serde_json::to_string_pretty(&metadata)?);

            Ok(())
        }
        Commands::Set(set_args) => {
            let env = get_env(&config, environment).await?;
            let (mount, path) = mount_and_path(&config, &env, &set_args.vault)?;
            // read json from file
            let data = tokio::fs::read(&set_args.input_file).await?;
            let value: HashMap<String, String> = serde_json::from_slice(&data)?;
            let set_response =
                vaultrs::kv2::set(&env.vault_client()?, &mount, &path, &value).await?;

            let mut metadata = SetSecretMetadataRequestBuilder::default();
            let mut custom_metadata: HashMap<String, String> = HashMap::new();

            custom_metadata.insert(
                "owner".into(),
                set_args
                    .metadata_owner
                    .clone()
                    .unwrap_or(config.default_owner),
            );

            if set_args.metadata_rotation.unwrap_or(false) {
                custom_metadata.insert(
                    "maxTTL".into(),
                    set_args.metadata_max_ttl.clone().unwrap_or("90d".into()),
                );
                custom_metadata.insert("mustRotate".into(), "true".into());
                custom_metadata.insert(
                    "lastRotation".into(),
                    Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string(),
                );
            }

            metadata.custom_metadata(custom_metadata);

            vaultrs::kv2::set_metadata(&env.vault_client()?, &mount, &path, Some(&mut metadata))
                .await?;

            println!(
                "set response: {}",
                serde_json::to_string_pretty(&set_response)?
            );
            Ok(())
        }
    }
}
