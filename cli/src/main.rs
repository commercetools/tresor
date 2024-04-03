use std::{collections::HashMap, path::PathBuf};

use clap::{command, Args, Parser, Subcommand};
use config::{config_file_path, get_env, load_or_create_config, ContextConfig, EnvironmentConfig};
use error::CliError;
use minijinja::Environment;

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
    /// context to use, use '*' for all
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
    #[clap(long, env = "VAULT_METADATA_OWNER")]
    metadata_owner: Option<String>,
    #[clap(long, env = "VAULT_METADATA_MAX_TTL")]
    metadata_max_ttl: Option<String>,
    #[clap(long, env = "VAULT_METADATA_ROTATION")]
    metadata_rotation: Option<bool>,
    /// date in format %Y-%m-%dT%H:%M:%S%.3fZ
    /// if not set current date time will be used
    #[clap(long, env = "VAULT_METADATA_ROTATION_DATE")]
    metadata_rotation_date: Option<String>,
}

fn replace_variables(
    template: &str,
    variables: &HashMap<String, String>,
) -> Result<String, CliError> {
    let env = Environment::new();
    let rendered = env.render_str(template, variables)?;
    Ok(rendered)
}

fn get_context(context_name: &str, env: &EnvironmentConfig) -> Result<ContextConfig, CliError> {
    let context = env
        .contexts
        .clone()
        .into_iter()
        .find_map(|context| {
            let context_lowercase = context.name.to_string().to_lowercase();
            if context_lowercase == context_name {
                Some(context)
            } else if context_lowercase.contains(context_name) {
                println!(
                    "found context '{}' via partial match of '{}'",
                    context.name, context_name
                );
                Some(context)
            } else {
                None
            }
        })
        .ok_or(CliError::RuntimeError(format!(
            "context {} not found in environment {}, must be part of:\n{}",
            context_name,
            env.name,
            env.contexts
                .iter()
                .map(|context| context.name.to_string())
                .collect::<Vec<String>>()
                .join("\n")
        )))?;
    Ok(context)
}

fn mount_and_path(
    env: &str,
    context: &ContextConfig,
    mount_template: Option<String>,
    path_template: Option<String>,
    path: Option<String>,
    service: Option<String>,
) -> Result<(String, String), CliError> {
    let mut replacement_values: HashMap<String, String> = HashMap::new();
    replacement_values.insert("context".into(), context.name.clone());
    replacement_values.insert("environment".into(), env.to_string());
    replacement_values.insert(
        "service".into(),
        service.clone().unwrap_or("default".into()),
    );
    replacement_values.insert("path".into(), path.clone().unwrap_or("default".into()));

    for (key, value) in context.variables.clone().unwrap_or_default() {
        replacement_values.insert(key, value);
    }

    let mount = replace_variables(&mount_template.unwrap_or_default(), &replacement_values)?;
    let path = replace_variables(&path_template.unwrap_or_default(), &replacement_values)?;

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
            let mut clean_config = config.clone();

            clean_config.environments.iter_mut().for_each(|env| {
                env.token = None;
                env.token_valid_until = None
            });

            println!(
                "config {}:{}",
                config_file.display(),
                serde_yaml::to_string(&clean_config)?
            );
            Ok(())
        }
        Commands::List(args) => {
            let env = get_env(&config, &args.env.environment).await?;
            let context = get_context(&args.context, &env)?;
            let (mount, path) = mount_and_path(
                &env.name,
                &context,
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
            let context = get_context(&args.context, &env)?;
            let (mount, path) = mount_and_path(
                &env.name,
                &context,
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
            let context = get_context(&set_args.context.context, &env)?;
            let (mount, path) = mount_and_path(
                &env.name,
                &context,
                config.mount_template.clone(),
                config.path_template.clone(),
                set_args.context.path.clone(),
                set_args.context.service.clone(),
            )?;

            // read json from file
            let data = tokio::fs::read(&set_args.input_file).await?;
            let value: HashMap<String, String> = serde_json::from_slice(&data)?;

            let set_response = env.vault()?.set_data(&mount, &path, value).await?;
            crate::vault::set_metadata(&config, &env.vault_client()?, &set_args.metadata, &mount, &path).await?;

            println!(
                "set response: {}",
                serde_json::to_string_pretty(&set_response)?
            );
            Ok(())
        }
        Commands::Patch(patch_args) => {
            let env = get_env(&config, &patch_args.context.env.environment).await?;
            let context = get_context(&patch_args.context.context, &env)?;
            let (mount, path) = mount_and_path(
                &env.name,
                &context,
                config.mount_template.clone(),
                config.path_template.clone(),
                patch_args.context.path.clone(),
                patch_args.context.service.clone(),
            )?;

            // read json from file
            let data = tokio::fs::read(&patch_args.input_file).await?;
            let value: HashMap<String, String> = serde_json::from_slice(&data)?;

            let set_response = env.vault()?.patch_data(&mount, &path, value).await?;
            crate::vault::set_metadata(&config, &env.vault_client()?, &patch_args.metadata, &mount, &path).await?;

            println!(
                "set response: {}",
                serde_json::to_string_pretty(&set_response)?
            );
            Ok(())
        }
        Commands::Sync(sync_args) => {
            let env = get_env(&config, &sync_args.context.env.environment).await?;
            let vault_client = &env.vault_client()?;
            let vault = env.vault()?;

            let contexts = match sync_args.context.context.as_str() {
                "*" => env.contexts,
                context_name => vec![get_context(context_name, &env)?],
            };

            for context in contexts {
                for mapping in &env.mappings.clone().unwrap_or_default() {
                    if mapping.skip.unwrap_or(false) {
                        println!("skipping mapping: {mapping:?}");
                        continue;
                    }

                    let source_value = match (mapping.source.clone(), mapping.value.clone()) {
                        (None, value) => value,
                        (Some(source_ref), None) => {
                            let read_source_values =
                                vaultrs::kv2::read::<HashMap<String, Option<String>>>(
                                    vault_client,
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
                                vault_client,
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
                                let set_response =             vault
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
                                    vault_client,
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
            }

            println!(
                "syncing environment {}, apply: {}",
                env.name, sync_args.apply
            );

            Ok(())
        }
    }
}
