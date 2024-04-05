use std::{collections::HashMap, fmt::Display, path::PathBuf};

use home::home_dir;

use minijinja::Environment;
use serde::{Deserialize, Serialize};
use tokio::{fs::File, io::AsyncWriteExt};
use vaultrs::client::VaultClient;

use crate::{
    console::Console,
    error::CliError,
    template::track_context,
    vault::{create_client, Vault},
    VaultContextArgs,
};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContextConfig {
    pub name: String,
    pub variables: Option<HashMap<String, String>>,
}

impl ContextConfig {
    pub fn replace_variables(
        &self,
        template: &str,
        env: &str,
        path: Option<String>,
        service: Option<String>,
    ) -> Result<String, CliError> {
        let mut replacement_values: HashMap<String, String> = HashMap::new();
        replacement_values.insert("context".into(), self.name.clone());
        replacement_values.insert("environment".into(), env.to_string());

        service.iter().for_each(|service_name| {
            replacement_values.insert("service".into(), service_name.to_string());
        });

        path.iter().for_each(|path_name| {
            replacement_values.insert("path".into(), path_name.to_string());
        });

        for (key, value) in self.variables.clone().unwrap_or_default() {
            replacement_values.insert(key, value);
        }

        let (variables, undefined) = track_context(replacement_values.into());

        let env = Environment::new();
        let rendered = env.render_str(template, variables)?;

        if rendered.contains("{") || rendered.contains("}") {
            return Err(CliError::TemplateError(format!(
                "curly braces found after template replace, this is considered an error: {rendered}"
            )));
        }

        let all_undefined = undefined.lock().unwrap().clone();

        if !all_undefined.is_empty() {
            return Err(CliError::TemplateError(format!(
                "found undefined values in template: {all_undefined:?}, you might need to specify them in the command options (e.g. --service)"
            )));
        }

        Ok(rendered)
    }

    pub fn mount_and_path(
        &self,
        context_args: &VaultContextArgs,
        config: &Config,
    ) -> Result<(String, String), CliError> {
        let env = &context_args.env.environment;
        let found_mount_template = config.mount_template(context_args.mount_template.clone());
        let found_path_template = config.path_template(context_args.path_template.clone());

        let mount_template = match (
            context_args.mount_template.clone(),
            found_mount_template.clone(),
        ) {
            (_, Some(template)) => template,
            (arg, None) => {
                return Err(CliError::CommandError(format!(
                    "no template found for arg: {:?}, default: {:?}",
                    arg,
                    config.default_mount_template.clone()
                )))
            }
        };

        let path_template = match (
            context_args.path_template.clone(),
            found_path_template.clone(),
        ) {
            (_, Some(template)) => template,
            (arg, None) => {
                return Err(CliError::CommandError(format!(
                    "no template found for arg: {:?}, default: {:?}",
                    arg,
                    config.default_path_template.clone()
                )))
            }
        };

        let mount = self.replace_variables(
            &mount_template,
            env,
            context_args.path.clone(),
            context_args.service.clone(),
        )?;
        let path = self.replace_variables(
            &path_template,
            env,
            context_args.path.clone(),
            context_args.service.clone(),
        )?;

        Ok((mount, path))
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct EnvironmentConfig {
    pub name: String,
    pub vault_address: String,
    pub token: Option<String>,
    pub token_valid_until: Option<u64>,
    pub contexts: Vec<ContextConfig>,
    pub auth_mount: Option<String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ValueRef {
    pub key: String,
    pub mount: String,
    pub path: String,
}

impl Display for ValueRef {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}/{}#{}", self.mount, self.path, self.key)
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ValueMapping {
    pub source: Option<ValueRef>,
    pub value: Option<String>,
    pub target: ValueRef,
    pub skip: Option<bool>,
}

impl Display for ValueMapping {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(
            f,
            "{} -> {} (skip: {})",
            self.source
                .clone()
                .map(|value_ref| value_ref.to_string())
                .or(self.value.clone())
                .unwrap_or("-".into()),
            self.target.to_string(),
            self.skip.unwrap_or(false)
        )
    }
}

impl EnvironmentConfig {
    pub fn auth_mount_or_default(&self) -> String {
        self.auth_mount
            .clone()
            .unwrap_or_else(|| "oidc/gsuite".into())
    }

    pub fn valid_token(&self) -> Result<String, CliError> {
        let now = chrono::Utc::now().timestamp() as u64;
        match (self.token.clone(), self.token_valid_until) {
            (Some(token), Some(valid_until)) if valid_until > now => Ok(token),
            _ => Err(CliError::AuthError(
                "No valid token found for this environment, you need to login again".to_string(),
            )),
        }
    }

    pub fn vault_client(&self) -> Result<VaultClient, CliError> {
        create_client(&self.vault_address, Some(self.valid_token()?))
    }

    pub fn vault(&self) -> Result<Vault, CliError> {
        Ok(Vault::create(&self.vault_address, &self.valid_token()?))
    }

    pub fn get_context(&self, context_name: &str) -> Result<ContextConfig, CliError> {
        let context = self
            .contexts
            .clone()
            .into_iter()
            .find_map(|context| {
                let context_lowercase = context.name.to_string().to_lowercase();
                if context_lowercase == context_name {
                    Some(context)
                } else if context_lowercase.contains(context_name) {
                    println!(
                        "{}",
                        Console::warning(format!(
                            "found context '{}' via partial match of '{}'",
                            context.name, context_name
                        ))
                    );
                    Some(context)
                } else {
                    None
                }
            })
            .ok_or(CliError::CommandError(format!(
                "context {} not found in environment {}, must be part of:\n{}",
                context_name,
                self.name,
                self.contexts
                    .iter()
                    .map(|context| context.name.to_string())
                    .collect::<Vec<String>>()
                    .join("\n")
            )))?;
        Ok(context)
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub default_owner: String,
    pub default_mount_template: Option<String>,
    pub default_path_template: Option<String>,
    pub mount_templates: Option<HashMap<String, String>>,
    pub path_templates: Option<HashMap<String, String>>,
    pub environments: Vec<EnvironmentConfig>,
    pub mappings: Option<Vec<ValueMapping>>,
}

impl Config {
    pub fn mount_template(&self, name: Option<String>) -> Option<String> {
        match name.or(self.default_mount_template.clone()) {
            Some(key) => self
                .mount_templates
                .clone()
                .unwrap_or_default()
                .get(&key)
                .cloned(),
            None => None,
        }
    }

    pub fn path_template(&self, name: Option<String>) -> Option<String> {
        match name.or(self.default_path_template.clone()) {
            Some(key) => self
                .path_templates
                .clone()
                .unwrap_or_default()
                .get(&key)
                .cloned(),
            None => None,
        }
    }
}

pub async fn config_file_path() -> Result<PathBuf, CliError> {
    let home = home_dir().ok_or(CliError::RuntimeError(
        "Unable to get your home dir!".to_string(),
    ))?;
    let config_dir = home.join(".config/tresor");
    tokio::fs::create_dir_all(&config_dir).await?;
    Ok(config_dir.join("config.yaml"))
}

// load a json config from the user home directory .config/tresor/config.json
// create an empty config and the config folder if they don't exist
pub async fn load_or_create_config() -> Result<Config, CliError> {
    let config_file = config_file_path().await?;

    if !config_file.exists() {
        println!(
            "# no existing config found, creating default in {}",
            config_file.display()
        );
    }

    let config = match tokio::fs::read(&config_file).await {
        Ok(data) => serde_yaml::from_slice(&data)?,
        Err(_) => {
            let default_config = Config::default();
            // write default config to file
            let data = serde_yaml::to_string(&default_config)?;
            tokio::fs::write(&config_file, data).await?;
            default_config
        }
    };
    Ok(config)
}

pub async fn get_env(config: &Config, name: &str) -> Result<EnvironmentConfig, CliError> {
    let env = config
        .environments
        .clone()
        .into_iter()
        .find_map(|env| {
            if env.name == name {
                Some(env)
            } else if env.name.starts_with(&name.to_string()) {
                Some(env)
            } else {
                None
            }
        })
        .ok_or(CliError::CommandError(format!(
            "Environment {} not found",
            name
        )))?;
    Ok(env)
}

pub async fn write_token(
    config: &mut Config,
    target_config: &EnvironmentConfig,
    token: &str,
    token_duration: u64,
) -> Result<Config, CliError> {
    let envs: Vec<EnvironmentConfig> = config
        .environments
        .clone()
        .iter_mut()
        .map(|env| {
            if env.name == target_config.name {
                env.token = Some(token.to_string());
                env.token_valid_until =
                    Some(chrono::Utc::now().timestamp() as u64 + token_duration);
                env.to_owned()
            } else {
                env.to_owned()
            }
        })
        .collect();
    config.environments = envs;

    let mut config_file = File::create(config_file_path().await?).await?;
    config_file
        .write_all(serde_yaml::to_string(&config)?.as_bytes())
        .await?;
    Ok(config.to_owned())
}

#[cfg(test)]
mod test {
    use std::collections::HashMap;

    use crate::{config::ContextConfig, error::CliError};

    #[tokio::test]
    async fn test_replacements() -> Result<(), CliError> {
        let mut variables: HashMap<String, String> = HashMap::new();
        variables.insert("var1".into(), "var1".into());

        let context = ContextConfig {
            name: "test".into(),
            variables: Some(variables),
        };

        // missing path and service
        assert!(context
            .replace_variables(
                "{{var1}}/{{environment}}/{{service}}/{{path}}",
                "env",
                None,
                None,
            )
            .is_err());

        assert_eq!(
            context.replace_variables(
                "{{var1}}/{{environment}}/{{service}}/{{path}}",
                "env",
                Some("test-path".into()),
                Some("test-service".into()),
            )?,
            "var1/env/test-service/test-path"
        );

        match context.replace_variables("{{var}}", "env", None, None) {
            Err(CliError::TemplateError(_)) => (),
            _ => {
                return Err(CliError::RuntimeError(
                    "undefined variables should create an error".into(),
                ))
            }
        }

        match context.replace_variables("{var}}", "env", None, None) {
            Err(CliError::TemplateError(_)) => (),
            _ => {
                return Err(CliError::RuntimeError(
                    "incomplete templates should create an error".into(),
                ))
            }
        }

        Ok(())
    }
}
