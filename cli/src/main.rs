use std::{collections::HashMap, path::PathBuf};

use clap::{command, Args, Parser, Subcommand};
use config::{config_file_path, get_env, load_or_create_config, Config, EnvironmentConfig};
use error::CliError;

mod config;
mod error;
mod vault;

#[derive(Parser, Debug)]
#[command(version, about, long_about = None)]
struct TresorArgs {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Debug, Clone, Args)]
struct VaultEnvArgs {
    /// vault environment to use: like staging or production
    #[clap(env = "VAULT_ENVIRONMENT")]
    environment: String,
}

#[derive(Debug, Clone, Args)]
struct VaultContextArgs {
    #[command(flatten)]
    env: VaultEnvArgs,
    /// context to use
    #[clap(env = "VAULT_CONTEXT")]
    context: String,
    /// service to use in template
    #[clap(short, long, env = "VAULT_SERVICE")]
    service: Option<String>,
    /// path to use
    #[clap(short, long, env = "VAULT_PATH")]
    path: Option<String>,
}

#[derive(Subcommand, Debug)]
enum Commands {
    Login {
        #[command(flatten)]
        vault: VaultEnvArgs,
        role: Option<String>,
    },
    List(VaultContextArgs),
    Get(VaultContextArgs),
    Set(SetCommandArgs),
    Patch(SetCommandArgs),
    Config,
    /// print the current token of the environment
    Token(VaultEnvArgs),
    Sync(SyncCommandArgs),
}

#[derive(Debug, Args)]
struct SyncCommandArgs {
    #[command(flatten)]
    env: VaultEnvArgs,

    /// changes will only be applied if this flag is set
    #[clap(long, env = "SYNC_APPLY", default_value_t = false)]
    apply: bool,

    #[command(flatten)]
    metadata: MetadataArgs,
}

#[derive(Debug, Args)]
struct SetCommandArgs {
    #[command(flatten)]
    context: VaultContextArgs,

    /// input with json object data
    input_file: PathBuf,

    #[command(flatten)]
    metadata: MetadataArgs,
}

#[derive(Debug, Args)]
struct MetadataArgs {
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
    args: &VaultContextArgs,
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
    let config = load_or_create_config().await?;

    match &args.command {
        Commands::Login { vault, role } => {
            vault::login(&config, &vault.environment, role.to_owned()).await?;
            Ok(())
        }
        Commands::Token(vault) => {
            let env = get_env(&config, &vault.environment).await?;
            let token = env.valid_token()?;
            println!("export VAULT_TOKEN={}", token);
            println!("export VAULT_ADDR={}", env.vault_address);
            println!("export VAULT_ADDRESS={}", env.vault_address);
            Ok(())
        }
        Commands::Config => {
            let config_file = config_file_path().await?;
            println!(
                "config {}:{}",
                config_file.display(),
                serde_json::to_string_pretty(&config)?
            );
            Ok(())
        }
        Commands::List(args) => {
            let env = get_env(&config, &args.env.environment).await?;
            let (mount, path) = mount_and_path(&config, &env, args)?;

            println!("listing secrets in {mount}/{path}:");

            let list = vaultrs::kv2::list(&env.vault_client()?, &mount, &path).await?;

            for entry in list {
                println!("{}", entry)
            }

            Ok(())
        }
        Commands::Get(args) => {
            let env = get_env(&config, &args.env.environment).await?;
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
            let env = get_env(&config, &set_args.context.env.environment).await?;
            let (mount, path) = mount_and_path(&config, &env, &set_args.context)?;
            // read json from file
            let data = tokio::fs::read(&set_args.input_file).await?;
            let value: HashMap<String, String> = serde_json::from_slice(&data)?;

            let set_response = env.vault()?.set_data(&mount, &path, value).await?;
            crate::vault::set_metadata(&config, &env, &set_args.metadata, &mount, &path).await?;

            println!(
                "set response: {}",
                serde_json::to_string_pretty(&set_response)?
            );
            Ok(())
        }
        Commands::Patch(patch_args) => {
            let env = get_env(&config, &patch_args.context.env.environment).await?;
            let (mount, path) = mount_and_path(&config, &env, &patch_args.context)?;
            // read json from file
            let data = tokio::fs::read(&patch_args.input_file).await?;
            let value: HashMap<String, String> = serde_json::from_slice(&data)?;

            let set_response = env.vault()?.patch_data(&mount, &path, value).await?;
            crate::vault::set_metadata(&config, &env, &patch_args.metadata, &mount, &path).await?;

            println!(
                "set response: {}",
                serde_json::to_string_pretty(&set_response)?
            );
            Ok(())
        }
        Commands::Sync(sync_args) => {
            let env = get_env(&config, &sync_args.env.environment).await?;
            println!(
                "syncing environment {}, apply: {}",
                env.name, sync_args.apply
            );

            for mapping in &env.mappings.clone().unwrap_or_default() {
                let source_value = match (mapping.source.clone(), mapping.value.clone()) {
                    (None, value) => value,
                    (Some(source_ref), None) => {
                        let read_source_values =
                            vaultrs::kv2::read::<HashMap<String, Option<String>>>(
                                &env.vault_client()?,
                                &source_ref.mount,
                                &source_ref.path,
                            )
                            .await;

                        let source_values = read_source_values.map_err(|e| {
                            CliError::RuntimeError(format!(
                                "unable to read source: {source_ref:?}: {}",
                                e
                            ))
                        })?;

                        let source_value = source_values.get(&source_ref.key).unwrap_or(&None);
                        source_value.clone()
                    }
                    _ => return Err(CliError::RuntimeError("invalid source mapping".into())),
                };

                match source_value {
                    Some(value) => {
                        let target = mapping.target.clone();

                        let read_target_values = vaultrs::kv2::read::<HashMap<String, String>>(
                            &env.vault_client()?,
                            &target.mount,
                            &target.path,
                        )
                        .await;

                        let mut target_values = read_target_values.map_err(|e| {
                            CliError::RuntimeError(format!(
                                "unable to read target: {target:?}: {}",
                                e
                            ))
                        })?;

                        target_values.insert(mapping.target.key.clone(), value.to_string());

                        if sync_args.apply {
                            let set_response = env
                                .vault()?
                                .set_data(
                                    &mapping.target.mount,
                                    &mapping.target.path,
                                    target_values.clone(),
                                )
                                .await;

                            set_response.map_err(|e| {
                                CliError::RuntimeError(format!(
                                    "unable to set target: {target:?}: {}",
                                    e
                                ))
                            })?;

                            let metadata_response = crate::vault::set_metadata(
                                &config,
                                &env,
                                &sync_args.metadata,
                                &target.mount,
                                &target.path,
                            )
                            .await;

                            metadata_response.map_err(|e| {
                                CliError::RuntimeError(format!(
                                    "unable to set metadata for target: {target:?}: {}",
                                    e
                                ))
                            })?;

                            println!("updated {target:?}, with value: {value}")
                        } else {
                            println!("would update {target:?} with value: {value}")
                        }
                    }
                    None => println!("no source value found for {mapping:?}"),
                }
            }
            Ok(())
        }
    }
}
