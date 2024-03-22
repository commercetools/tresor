use std::fmt;

use actix_web::ResponseError;
use serde::{Deserialize, Serialize};
use vaultrs::{
    api::kv2::requests::SetSecretMetadataRequestBuilderError,
    client::VaultClientSettingsBuilderError, error::ClientError,
};

#[derive(Serialize, Deserialize, Clone)]
pub enum CliError {
    RuntimeError(String),
    VaultError(String),
}

impl std::error::Error for CliError {}

impl fmt::Debug for CliError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::RuntimeError(reason) => write!(f, "RuntimeError: {}", reason),
            Self::VaultError(reason) => write!(f, "VaultError: {}", reason),
        }
    }
}

impl fmt::Display for CliError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::RuntimeError(reason) => write!(f, "RuntimeError: {}", reason),
            Self::VaultError(reason) => write!(f, "VaultError: {}", reason),
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

impl From<SetSecretMetadataRequestBuilderError> for CliError {
    fn from(error: SetSecretMetadataRequestBuilderError) -> Self {
        Self::RuntimeError(error.to_string())
    }
}

impl ResponseError for CliError {}
