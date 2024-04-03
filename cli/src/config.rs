use std::{collections::HashMap, path::PathBuf};

use home::home_dir;

use serde::{Deserialize, Serialize};
use tokio::{fs::File, io::AsyncWriteExt};
use vaultrs::client::VaultClient;

use crate::{
    error::CliError,
    vault::{create_client, Vault},
};

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ContextConfig {
    pub name: String,
    pub variables: Option<HashMap<String, String>>,
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
    pub mappings: Option<Vec<ValueMapping>>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ValueRef {
    pub mount: String,
    pub path: String,
    pub key: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ValueMapping {
    pub source: Option<ValueRef>,
    pub value: Option<String>,
    pub target: ValueRef,
    pub skip: Option<bool>,
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
            _ => Err(CliError::RuntimeError(
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
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Config {
    pub default_owner: String,
    pub mount_template: Option<String>,
    pub path_template: Option<String>,
    pub environments: Vec<EnvironmentConfig>,
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
        .ok_or(CliError::RuntimeError(format!(
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
