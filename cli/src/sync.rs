use std::collections::HashMap;

use vaultrs::error::ClientError;

use crate::{config::EnvironmentConfig, console::Console, error::CliError, SyncCommandArgs};

pub async fn sync_mappings(
    env: &EnvironmentConfig,
    sync_args: &SyncCommandArgs,
    default_owner: &str,
) -> Result<(), CliError> {
    println!(
        "syncing environment {}, apply: {}",
        Console::highlight(&env.name),
        Console::warning(sync_args.apply)
    );

    let vault_client = &env.vault_client()?;
    let vault = &env.vault()?;

    let contexts = match sync_args.context.context.as_str() {
        "*" => env.contexts.clone(),
        context_name => vec![env.get_context(context_name)?],
    };

    for context in contexts {
        println!(
            "syncing mappings for context {}",
            Console::highlight(&context.name)
        );

        for mapping in &env.mappings.clone().unwrap_or_default() {
            if mapping.skip.unwrap_or(false) {
                println!("{}: {mapping}", Console::highlight("skipping mapping"));
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
                            "unable to read source: {source_ref}: {}",
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
                Some(source_value) => {
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

                    let mut target_values = match read_target_values {
                        Ok(values) => values,
                        Err(ClientError::APIError { code, errors: _ }) if code == 404 => {
                            HashMap::new()
                        }
                        Err(err) => {
                            return Err(CliError::RuntimeError(format!(
                                "unable to read target: {target}: {}",
                                err
                            )))
                        }
                    };

                    target_values.insert(target.key.clone(), source_value.to_string());

                    let target_message_part = format!("{target_mount}/{target_path}");
                    let source_value_message_part = format!(
                        "{}XXXX",
                        Console::highlight(
                            source_value.chars().into_iter().take(4).collect::<String>()
                        )
                    );

                    if sync_args.apply {
                        let set_response = vault
                            .set_data(&target_mount, &target_path, target_values.clone())
                            .await;

                        set_response.map_err(|e| {
                            CliError::RuntimeError(format!("unable to set target: {target}: {}", e))
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
                                "unable to set metadata for target: {target_message_part}: {}",
                                e
                            ))
                        })?;

                        println!(
                            "{} {target_message_part}, with value: {source_value_message_part}",
                            Console::success("updated")
                        )
                    } else {
                        println!(
                            "{} {target_message_part} with value: {source_value_message_part}",
                            Console::warning("would update")
                        )
                    }
                }
                None => println!("no source value found for {mapping:?}"),
            }
        }
    }

    Ok(())
}

// the tests depend on the docker-compose setup in the project root
#[cfg(test)]
mod test {
    use std::collections::HashMap;

    use serde_json::json;

    use crate::{
        config::{ContextConfig, ValueMapping, ValueRef},
        error::CliError,
        sync::sync_mappings,
        MetadataArgs, SyncCommandArgs, VaultContextArgs, VaultEnvArgs,
    };

    #[tokio::test]
    async fn test_sync_mappings() -> Result<(), CliError> {
        let mut variables: HashMap<String, String> = HashMap::new();
        variables.insert("var".into(), "path-from-var".into());

        let mappings = vec![
            ValueMapping {
                source: None,
                value: Some("source value".into()),
                target: ValueRef {
                    mount: "{{service}}".into(),
                    path: "{{path}}".into(),
                    key: "test-field".into(),
                },
                skip: None,
            },
            ValueMapping {
                value: None,
                source: Some(ValueRef {
                    mount: "{{service}}".into(),
                    path: "{{path}}".into(),
                    key: "test-field".into(),
                }),
                target: ValueRef {
                    mount: "{{service}}".into(),
                    path: "{{path}}/{{var}}".into(),
                    key: "mapped-field".into(),
                },
                skip: None,
            },
        ];

        let env = crate::config::EnvironmentConfig {
            name: "test".to_string(),
            vault_address: "http://localhost:8200".into(),
            token: Some("vault-plaintext-root-token".into()),
            token_valid_until: Some(u64::MAX),
            contexts: vec![ContextConfig {
                name: "context".into(),
                variables: Some(variables),
            }],
            auth_mount: None,
            mappings: Some(mappings),
        };

        let sync_args = SyncCommandArgs {
            apply: true,
            context: VaultContextArgs {
                env: VaultEnvArgs {
                    environment: "test".into(),
                },
                context: "*".into(),
                service: Some("secret".into()),
                path: Some("test-path".into()),
            },
            metadata: MetadataArgs {
                metadata_rotation: Some(true),
                metadata_owner: None,
                metadata_max_ttl: None,
                metadata_rotation_date: Some("2024-04-04T10:10:10.000Z".into()),
            },
        };

        sync_mappings(&env, &sync_args, "test-owner").await?;

        let value =
            vaultrs::kv2::read::<serde_json::Value>(&env.vault_client()?, "secret", "test-path")
                .await?;

        assert_eq!(value, json!({ "test-field": "source value" }));

        let value = vaultrs::kv2::read::<serde_json::Value>(
            &env.vault_client()?,
            "secret",
            "test-path/path-from-var",
        )
        .await?;

        assert_eq!(value, json!({ "mapped-field": "source value" }));

        let metadata =
            vaultrs::kv2::read_metadata(&env.vault_client()?, "secret", "test-path/path-from-var")
                .await?;

        let current_metadata = metadata.custom_metadata.unwrap_or_default();
        assert_eq!(
            current_metadata.get("mustRotate"),
            Some(&"true".to_string())
        );

        assert_eq!(current_metadata.get("maxTTL"), Some(&"90d".to_string()));
        assert_eq!(
            current_metadata.get("owner"),
            Some(&"test-owner".to_string())
        );
        assert_eq!(
            current_metadata.get("lastRotation"),
            Some(&"2024-04-04T10:10:10.000Z".to_string())
        );

        Ok(())
    }
}
