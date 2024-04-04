use std::{collections::HashMap, path::PathBuf};

use clap::{command, Args, Parser, Subcommand};
use config::{config_file_path, get_env, load_or_create_config};
use console::Console;
use error::CliError;

mod config;
mod console;
mod error;
mod sync;
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
    #[clap(env = "TRESOR_ENVIRONMENT")]
    environment: String,
}

#[derive(Debug, Clone, Args)]
struct VaultContextArgs {
    #[command(flatten)]
    env: VaultEnvArgs,
    /// context to use, use '*' for all
    #[clap(env = "TRESOR_CONTEXT")]
    context: String,
    /// service to use in template
    #[clap(short, long, env = "TRESOR_SERVICE")]
    service: Option<String>,
    /// path to use
    #[clap(short, long, env = "TRESOR_PATH")]
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
    context: VaultContextArgs,

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
    #[clap(long, env = "TRESOR_METADATA_OWNER")]
    metadata_owner: Option<String>,
    #[clap(long, env = "TRESOR_METADATA_MAX_TTL")]
    metadata_max_ttl: Option<String>,
    #[clap(long, env = "TRESOR_METADATA_ROTATION")]
    metadata_rotation: Option<bool>,
    /// date in format %Y-%m-%dT%H:%M:%S%.3fZ
    /// if not set current date time will be used
    #[clap(long, env = "TRESOR_METADATA_ROTATION_DATE")]
    metadata_rotation_date: Option<String>,
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
            let mut clean_config = config.clone();

            clean_config.environments.iter_mut().for_each(|env| {
                env.token = None;
                env.token_valid_until = None
            });

            println!(
                "config {}:{}",
                Console::highlight(config_file.display()),
                serde_yaml::to_string(&clean_config)?
            );
            Ok(())
        }
        Commands::List(args) => {
            let env = get_env(&config, &args.env.environment).await?;
            let context = env.get_context(&args.context)?;
            let (mount, path) = context.mount_and_path(
                &env.name,
                config.mount_template,
                config.path_template,
                args.path.clone(),
                args.service.clone(),
            )?;

            println!("listing secrets in {mount}/{path}:");

            let list = vaultrs::kv2::list(&env.vault_client()?, &mount, &path).await?;

            for entry in list {
                println!("{}", entry)
            }

            Ok(())
        }
        Commands::Get(args) => {
            let env = get_env(&config, &args.env.environment).await?;
            let context = env.get_context(&args.context)?;
            let (mount, path) = context.mount_and_path(
                &env.name,
                config.mount_template,
                config.path_template,
                args.path.clone(),
                args.service.clone(),
            )?;

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
            let context = env.get_context(&set_args.context.context)?;
            let (mount, path) = context.mount_and_path(
                &env.name,
                config.mount_template.clone(),
                config.path_template.clone(),
                set_args.context.path.clone(),
                set_args.context.service.clone(),
            )?;

            // read json from file
            let data = tokio::fs::read(&set_args.input_file).await?;
            let value: HashMap<String, String> = serde_json::from_slice(&data)?;

            let set_response = env.vault()?.set_data(&mount, &path, value).await?;
            crate::vault::set_metadata(
                &env.vault_client()?,
                &set_args.metadata,
                &config.default_owner,
                &mount,
                &path,
            )
            .await?;

            println!(
                "set response: {}",
                Console::highlight(serde_json::to_string_pretty(&set_response)?)
            );
            Ok(())
        }
        Commands::Patch(patch_args) => {
            let env = get_env(&config, &patch_args.context.env.environment).await?;
            let context = env.get_context(&patch_args.context.context)?;
            let (mount, path) = context.mount_and_path(
                &env.name,
                config.mount_template.clone(),
                config.path_template.clone(),
                patch_args.context.path.clone(),
                patch_args.context.service.clone(),
            )?;

            // read json from file
            let data = tokio::fs::read(&patch_args.input_file).await?;
            let value: HashMap<String, String> = serde_json::from_slice(&data)?;

            let set_response = env.vault()?.patch_data(&mount, &path, value).await?;
            crate::vault::set_metadata(
                &env.vault_client()?,
                &patch_args.metadata,
                &config.default_owner,
                &mount,
                &path,
            )
            .await?;

            println!(
                "set response: {}",
                serde_json::to_string_pretty(&set_response)?
            );
            Ok(())
        }
        Commands::Sync(sync_args) => {
            let env = &get_env(&config, &sync_args.context.env.environment).await?;
            crate::sync::sync_mappings(&env, sync_args, &config.default_owner).await?;
            Ok(())
        }
    }
}
