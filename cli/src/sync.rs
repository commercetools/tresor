use std::collections::HashMap;

use crate::{config::EnvironmentConfig, console::Console, error::CliError, SyncCommandArgs};

pub async fn sync_mappings(
    env: &EnvironmentConfig,
    sync_args: &SyncCommandArgs,
    default_owner: &str,
) -> Result<(), CliError> {
    println!(
        "syncing environment {}, apply: {}",
        Console::highlight(&env.name),
        sync_args.apply
    );

    let vault_client = &env.vault_client()?;
    let vault = &env.vault()?;

    let contexts = match sync_args.context.context.as_str() {
        "*" => env.contexts.clone(),
        context_name => vec![env.get_context(context_name)?],
    };

    for context in contexts {
        for mapping in &env.mappings.clone().unwrap_or_default() {
            if mapping.skip.unwrap_or(false) {
                println!("{}: {mapping:?}", Console::highlight("skipping mapping"));
                continue;
            }

            let source_value = match (mapping.source.clone(), mapping.value.clone()) {
                (None, value) => value,
                (Some(source_ref), None) => {
                    let (source_mount, source_path) = context.mount_and_path(
                        &env.name,
                        Some(source_ref.mount.to_string()),
                        Some(source_ref.path.to_string()),
                        sync_args.context.path.clone(),
                        sync_args.context.service.clone(),
                    )?;
                    let read_source_values = vaultrs::kv2::read::<HashMap<String, Option<String>>>(
                        vault_client,
                        &source_mount,
                        &source_path,
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
                _ => {
                    return Err(CliError::RuntimeError(Console::error(
                        "invalid source mapping",
                    )))
                }
            };

            match source_value {
                Some(value) => {
                    let target = mapping.target.clone();

                    let (target_mount, target_path) = context.mount_and_path(
                        &env.name,
                        Some(target.mount.to_string()),
                        Some(target.path.to_string()),
                        sync_args.context.path.clone(),
                        sync_args.context.service.clone(),
                    )?;

                    let read_target_values = vaultrs::kv2::read::<HashMap<String, String>>(
                        vault_client,
                        &target_mount,
                        &target_path,
                    )
                    .await;

                    let mut target_values = read_target_values.map_err(|e| {
                        CliError::RuntimeError(format!("unable to read target: {target:?}: {}", e))
                    })?;

                    target_values.insert(target.key.clone(), value.to_string());

                    if sync_args.apply {
                        let set_response = vault
                            .set_data(&target_mount, &target_path, target_values.clone())
                            .await;

                        set_response.map_err(|e| {
                            CliError::RuntimeError(format!(
                                "unable to set target: {target:?}: {}",
                                e
                            ))
                        })?;

                        let metadata_response = crate::vault::set_metadata(
                            vault_client,
                            &sync_args.metadata,
                            &default_owner,
                            &target_mount,
                            &target_path,
                        )
                        .await;

                        metadata_response.map_err(|e| {
                            CliError::RuntimeError(format!(
                                "unable to set metadata for target: {target:?}: {}",
                                e
                            ))
                        })?;

                        println!(
                            "{} {target:?}, with value: {value}",
                            Console::success("updated")
                        )
                    } else {
                        println!("would update {target:?} with value: {value}")
                    }
                }
                None => println!("no source value found for {mapping:?}"),
            }
        }
    }

    Ok(())
}
