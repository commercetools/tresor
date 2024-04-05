use std::{collections::HashMap, path::PathBuf};

use clap::{command, Args, Parser, Subcommand};
use config::{config_file_path, get_env, load_or_create_config, Config};
use console::Console;
use error::CliError;

use crate::console::json_to_table_string;
mod config;
mod console;
mod error;
mod sync;
mod template;
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
    /// context to use, use '*' for all context in the sync command
    #[clap(env = "TRESOR_CONTEXT")]
    context: String,
    /// service to use in the templates
    #[clap(short, long, env = "TRESOR_SERVICE")]
    service: Option<String>,
    /// path to use in the templates
    #[clap(short, long, env = "TRESOR_PATH")]
    path: Option<String>,

    /// mount template to use, currently does nothing in sync command
    #[clap(short, long, env = "TRESOR_MOUNT_TEMPLATE")]
    mount_template: Option<String>,
    /// path template to use, currently does nothing in sync command
    #[clap(long, env = "TRESOR_PATH_TEMPLATE")]
    path_template: Option<String>,
}

#[derive(Subcommand, Debug)]
enum Commands {
    /// login and store the token in the environment config
    Login {
        #[command(flatten)]
        vault: VaultEnvArgs,
        role: Option<String>,
    },
    /// list paths in context
    List(VaultContextArgs),
    /// get values in context
    Get(GetCommandArgs),
    /// set values using the put method
    Set(SetCommandArgs),
    /// set values using the patch method
    Patch(SetCommandArgs),
    /// show current config without tokens
    Config,
    /// print the current token of the environment
    Token(VaultEnvArgs),
    /// sync the value mappings in the environment configuration
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

    /// show the values that will be set, default is false, only the first characters are shown
    #[clap(long, env = "SYNC_SHOW_VALUES", default_value_t = false)]
    show_values: bool,
}

#[derive(Debug, Args)]
struct GetCommandArgs {
    #[command(flatten)]
    context: VaultContextArgs,

    /// show the values returned, default is false, only the first characters are shown
    #[clap(long, env = "GET_SHOW_VALUES", default_value_t = false)]
    show_values: bool,
}

#[derive(Debug, Args)]
struct SetCommandArgs {
    #[command(flatten)]
    context: VaultContextArgs,

    /// input with json object data
    input_file: PathBuf,

    #[command(flatten)]
    metadata: MetadataArgs,

    /// only metadata will be set
    #[clap(long, env = "TRESOR_METADATA_ONLY", default_value_t = false)]
    metadata_only: bool,
}

#[derive(Debug, Args)]
struct MetadataArgs {
    /// set to true to set rotation related metadata fields
    #[clap(long, env = "TRESOR_METADATA_ROTATION")]
    metadata_rotation: Option<bool>,
    #[clap(long, env = "TRESOR_METADATA_OWNER")]
    metadata_owner: Option<String>,
    #[clap(long, env = "TRESOR_METADATA_MAX_TTL")]
    metadata_max_ttl: Option<String>,
    /// date in format %Y-%m-%dT%H:%M:%S%.3fZ
    /// if not set current date time will be used
    #[clap(long, env = "TRESOR_METADATA_ROTATION_DATE")]
    metadata_rotation_date: Option<String>,
}

#[tokio::main]
async fn main() -> Result<(), CliError> {
    let args = &TresorArgs::parse();
    let config = load_or_create_config().await?;
    let run = run_command(args, config).await?;
    Ok(run)
}

async fn run_command(args: &TresorArgs, config: Config) -> Result<(), CliError> {
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
                "config {}:\n{}",
                Console::highlight(config_file.display()),
                serde_yaml::to_string(&clean_config)?
            );
            Ok(())
        }
        Commands::List(args) => {
            let env = get_env(&config, &args.env.environment).await?;
            let context = env.get_context(&args.context)?;
            let (mount, path) = context.mount_and_path(&env, args, &config)?;

            println!("listing secrets in {mount}/{path}:");

            let list = vaultrs::kv2::list(&env.vault_client()?, &mount, &path).await?;

            for entry in list {
                println!("{}", entry)
            }

            Ok(())
        }
        Commands::Get(args) => {
            let env = get_env(&config, &args.context.env.environment).await?;
            let context = env.get_context(&args.context.context)?;
            let (mount, path) = context.mount_and_path(&env, &args.context, &config)?;
            println!("{mount}/{path}:");

            let value =
                vaultrs::kv2::read::<serde_json::Value>(&env.vault_client()?, &mount, &path)
                    .await?;

            println!("{}", json_to_table_string(&value, !args.show_values)?);

            let mut metadata =
                vaultrs::kv2::read_metadata(&env.vault_client()?, &mount, &path).await?;
            metadata.versions = HashMap::new();

            println!(
                "metadata:\n{}",
                json_to_table_string(&serde_json::to_value(metadata)?, false)?
            );

            Ok(())
        }
        Commands::Set(set_args) => {
            let env = get_env(&config, &set_args.context.env.environment).await?;
            let context = env.get_context(&set_args.context.context)?;
            let (mount, path) = context.mount_and_path(&env, &set_args.context, &config)?;

            // read json from file
            let data = tokio::fs::read(&set_args.input_file).await?;
            let value: HashMap<String, String> = serde_json::from_slice(&data)?;

            if !set_args.metadata_only {
                let set_response = env.vault()?.set_data(&mount, &path, value).await?;
                println!(
                    "set response: {}",
                    json_to_table_string(&serde_json::to_value(set_response)?, false)?
                );
            } else {
                println!("{}", Console::warning("only updating metadata"));
            };

            crate::vault::set_metadata(
                &env.vault_client()?,
                &set_args.metadata,
                &config.default_owner,
                &mount,
                &path,
            )
            .await?;

            println!("{}", Console::success("metadata updated"));

            Ok(())
        }
        Commands::Patch(patch_args) => {
            let env = get_env(&config, &patch_args.context.env.environment).await?;
            let context = env.get_context(&patch_args.context.context)?;
            let (mount, path) = context.mount_and_path(&env, &patch_args.context, &config)?;

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
                json_to_table_string(&serde_json::to_value(set_response)?, false)?
            );
            Ok(())
        }
        Commands::Sync(sync_args) => {
            match &config.mappings {
                Some(_) => {
                    crate::sync::sync_mappings(&sync_args, &config).await?;
                }
                None => println!("{}", Console::warning("no mappings configured")),
            };

            Ok(())
        }
    }
}
