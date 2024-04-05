use std::fmt;

use actix_web::ResponseError;
use serde::{Deserialize, Serialize};
use vaultrs::{
    api::kv2::requests::SetSecretMetadataRequestBuilderError,
    client::VaultClientSettingsBuilderError, error::ClientError,
};

use crate::console::Console;

#[derive(Serialize, Deserialize, Clone)]
pub enum CliError {
    RuntimeError(String),
    VaultError(String),
    TemplateError(String),
    CommandError(String),
    AuthError(String),
}

impl std::error::Error for CliError {}

impl fmt::Debug for CliError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self)
    }
}

impl fmt::Display for CliError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::RuntimeError(reason) => {
                write!(
                    f,
                    "{}: {}",
                    Console::emph("general error"),
                    Console::error(reason)
                )
            }
            Self::VaultError(reason) => write!(f, "vault error: {}", Console::error(reason)),
            Self::TemplateError(reason) => write!(f, "template error: {}", Console::error(reason)),
            Self::CommandError(reason) => write!(f, "command error: {}", Console::error(reason)),
            Self::AuthError(reason) => write!(f, "auth error: {}", Console::error(reason)),
        }
    }
}

impl From<std::io::Error> for CliError {
    fn from(error: std::io::Error) -> Self {
        Self::VaultError(error.to_string())
    }
}

impl From<serde_json::Error> for CliError {
    fn from(error: serde_json::Error) -> Self {
        Self::RuntimeError(error.to_string())
    }
}

impl From<ClientError> for CliError {
    fn from(error: ClientError) -> Self {
        Self::RuntimeError(error.to_string())
    }
}

impl From<VaultClientSettingsBuilderError> for CliError {
    fn from(error: VaultClientSettingsBuilderError) -> Self {
        Self::RuntimeError(error.to_string())
    }
}

impl From<reqwest::Error> for CliError {
    fn from(error: reqwest::Error) -> Self {
        Self::RuntimeError(error.to_string())
    }
}

impl From<serde_yaml::Error> for CliError {
    fn from(error: serde_yaml::Error) -> Self {
        Self::RuntimeError(error.to_string())
    }
}

impl From<SetSecretMetadataRequestBuilderError> for CliError {
    fn from(error: SetSecretMetadataRequestBuilderError) -> Self {
        Self::RuntimeError(error.to_string())
    }
}

impl From<minijinja::Error> for CliError {
    fn from(error: minijinja::Error) -> Self {
        Self::RuntimeError(error.to_string())
    }
}

impl ResponseError for CliError {}
