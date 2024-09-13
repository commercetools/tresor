use std::collections::HashMap;

use actix_web::{dev::Server, get, web, App, HttpResponse, HttpServer};
use chrono::Utc;
use once_cell::sync::Lazy;
use serde::{de::DeserializeOwned, Deserialize, Serialize};
use tokio::process::Command;
use tokio::sync::Mutex;
use vaultrs::{
    api::kv2::requests::SetSecretMetadataRequestBuilder,
    client::{VaultClient, VaultClientSettingsBuilder},
};

use crate::{
    config::{get_env, load_or_create_config, write_token, Config, EnvironmentConfig},
    console::Console,
    error::CliError,
    MetadataArgs,
};

static AUTH_RESPONSE: Lazy<Mutex<Option<VaultAuthResponse>>> = Lazy::new(|| Mutex::new(None));
static SHUTDOWN_SIGNAL: Lazy<Mutex<bool>> = Lazy::new(|| Mutex::new(false));

#[derive(Debug, Deserialize)]
#[allow(dead_code)]
pub struct CallbackParams {
    state: String,
    code: String,
    scope: String,
    hd: Option<String>,
    prompt: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
#[allow(dead_code)]
pub struct VaultAuthResponse {
    pub auth: AuthInfo,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[allow(dead_code)]
pub struct VaultSecretResponse {
    pub lease_duration: Option<u64>,
    pub lease_id: Option<String>,
    pub data: serde_json::Value,
    pub renewable: Option<bool>,
    pub request_id: Option<String>,
}

#[derive(Deserialize, Clone, Debug, Serialize)]
pub struct VaultSecretMetadata {
    pub created_time: String,
    pub deletion_time: String,
    pub custom_metadata: Option<HashMap<String, String>>,
    pub destroyed: bool,
    pub version: u64,
}

#[derive(Clone, Debug, Deserialize)]
#[allow(dead_code)]
pub struct AuthInfo {
    pub client_token: String,
    pub identity_policies: Vec<String>,
    pub lease_duration: u64,
}

pub fn create_client(vault_url: &str, token: Option<String>) -> Result<VaultClient, CliError> {
    let mut settings = VaultClientSettingsBuilder::default();
    settings.address(vault_url);

    if let Some(token) = token {
        settings.token(token);
    }

    let client = VaultClient::new(settings.build()?)?;
    Ok(client)
}

pub struct Vault {
    pub vault_url: String,
    pub token: String,
}

impl Vault {
    pub fn create(vault_url: &str, token: &str) -> Vault {
        Vault {
            vault_url: vault_url.to_owned(),
            token: token.to_owned(),
        }
    }

    pub async fn set_data(
        &self,
        mount: &str,
        path: &str,
        data: HashMap<String, String>,
    ) -> Result<VaultSecretResponse, CliError> {
        let path = format!("{mount}/data/{path}");
        let result = post::<VaultSecretResponse>(
            &self.vault_url,
            &self.token,
            &path,
            serde_json::json!({ "data": data }),
        )
        .await?;

        Ok(result)
    }

    pub async fn patch_data(
        &self,
        mount: &str,
        path: &str,
        data: HashMap<String, String>,
    ) -> Result<VaultSecretResponse, CliError> {
        let path = format!("{mount}/data/{path}");
        let result = patch::<VaultSecretResponse>(
            &self.vault_url,
            &self.token,
            &path,
            serde_json::json!({ "data": data }),
        )
        .await?;

        println!("patch data: {:?}", result);

        Ok(result)
    }
}

async fn post<T: DeserializeOwned>(
    vault_url: &str,
    token: &str,
    path: &str,
    data: serde_json::Value,
) -> Result<T, CliError> {
    let client = reqwest::Client::new();
    let url = format!("{}/v1/{}", vault_url, path);
    let response = client
        .post(&url)
        .header("X-Vault-Token", token)
        .header("X-Vault-Request", true.to_string())
        .header("Content-Type", "application/json")
        .json(&data)
        .send()
        .await?;

    if !response.status().is_success() {
        return Err(CliError::VaultError(format!(
            "Vault returned an error: {}, {}",
            response.status(),
            response.text().await?
        )));
    }

    let body_string = response.text().await?;

    let parsed = serde_json::from_str::<T>(&body_string);

    match parsed {
        Ok(result) => Ok(result),
        Err(e) => {
            println!("error parsing response: {}", body_string);
            Err(CliError::VaultError(format!(
                "Error parsing response: {}",
                e.to_string()
            )))
        }
    }
}

async fn patch<T: DeserializeOwned>(
    vault_url: &str,
    token: &str,
    path: &str,
    data: serde_json::Value,
) -> Result<T, CliError> {
    let client = reqwest::Client::new();
    let url = format!("{}/v1/{}", vault_url, path);
    let response = client
        .patch(url)
        .header("X-Vault-Token", token)
        .header("X-Vault-Request", true.to_string())
        .header("Content-Type", "application/merge-patch+json")
        .json(&data)
        .send()
        .await?;

    if !response.status().is_success() {
        return Err(CliError::VaultError(format!(
            "Vault returned an error: {}, {}",
            response.status(),
            response.text().await?
        )));
    }

    let body_string = response.text().await?;

    let parsed = serde_json::from_str::<T>(&body_string);

    match parsed {
        Ok(result) => Ok(result),
        Err(e) => {
            println!("error parsing response: {}", body_string);
            Err(CliError::VaultError(format!(
                "Error parsing response: {}",
                e.to_string()
            )))
        }
    }
}

pub async fn set_metadata(
    client: &VaultClient,
    custom_metadata: HashMap<String, String>,
    mount: &str,
    path: &str,
) -> Result<(), CliError> {
    let mut metadata_request = SetSecretMetadataRequestBuilder::default();
    metadata_request.custom_metadata(custom_metadata);

    vaultrs::kv2::set_metadata(client, &mount, &path, Some(&mut metadata_request)).await?;
    Ok(())
}

pub fn now_date_string() -> String {
    Utc::now().format("%Y-%m-%dT%H:%M:%S%.3fZ").to_string()
}

pub async fn set_metadata_from_args(
    client: &VaultClient,
    metadata: &MetadataArgs,
    default_owner: &str,
    mount: &str,
    path: &str,
) -> Result<(), CliError> {
    let mut custom_metadata: HashMap<String, String> = HashMap::new();

    custom_metadata.insert(
        "owner".into(),
        metadata
            .metadata_owner
            .clone()
            .unwrap_or(default_owner.to_string()),
    );

    if metadata.metadata_rotation.unwrap_or(false) {
        custom_metadata.insert(
            "maxTTL".into(),
            metadata.metadata_max_ttl.clone().unwrap_or("90d".into()),
        );
        custom_metadata.insert("mustRotate".into(), "true".into());
        custom_metadata.insert(
            "lastRotation".into(),
            metadata
                .metadata_rotation_date
                .clone()
                .unwrap_or_else(|| now_date_string())
                .into(),
        );
    } else {
        println!(
            "{}",
            Console::warning("not setting rotation metadata (see command options)")
        )
    }

    self::set_metadata(client, custom_metadata, mount, path).await
}

#[get("/oidc/callback")]
async fn oidc_callback(
    env: web::Data<EnvironmentConfig>,
    params: web::Query<CallbackParams>,
) -> Result<HttpResponse, CliError> {
    let auth_response = vault_callback(env.get_ref().to_owned(), params.into_inner()).await?;
    AUTH_RESPONSE.lock().await.replace(auth_response);
    Ok(HttpResponse::Ok().body("authentication successful"))
}

async fn vault_callback(
    env: EnvironmentConfig,
    params: CallbackParams,
) -> Result<VaultAuthResponse, CliError> {
    let url = format!(
        "{}/v1/auth/{}/oidc/callback",
        env.vault_address,
        env.auth_mount_or_default()
    );

    let client = reqwest::Client::new();
    let mut query_params = HashMap::<String, String>::new();
    query_params.insert("code".into(), params.code);
    query_params.insert("state".into(), params.state);

    let resp = client
        .get(url)
        .query(&query_params)
        .header("X-Vault-Request", "true")
        .send()
        .await?;

    if !resp.status().is_success() {
        return Err(CliError::AuthError(format!(
            "vault returned an error in auth callback: {}, {}",
            resp.status(),
            resp.text().await?
        )));
    }

    let auth = resp.json::<VaultAuthResponse>().await?;
    Ok(auth)
}

async fn start_callback_server(env: EnvironmentConfig) -> std::io::Result<Server> {
    Ok(HttpServer::new(move || {
        App::new()
            .service(oidc_callback)
            .app_data(web::Data::new(env.clone()))
    })
    .bind(("0.0.0.0", 8250))?
    .run())
}

async fn execute_open_command(url: impl AsRef<str>, cmd: &str) {
    let output = Command::new(cmd).args([url.as_ref()]).output().await;
    match output {
        Ok(_) => (),
        Err(_) => {
            println!("auth url: {}", cmd.to_string());
        }
    }
}

#[cfg(target_os = "macos")]
async fn print_or_open_browser(url: String) {
    execute_open_command(url, "open").await
}

#[cfg(target_os = "linux")]
async fn print_or_open_browser(url: String) {
    execute_open_command(url, "xdg-open").await
}

// And this function only gets compiled if the target OS is *not* linux
#[cfg(all(not(target_os = "linux"), not(target_os = "macos")))]
fn print_or_open_browser(url: String) {
    println!("auth url: {}", url);
}

pub async fn login(
    config: &Config,
    environment: &str,
    role: Option<String>,
) -> Result<VaultAuthResponse, CliError> {
    let env = get_env(&config, &environment).await?;

    let callback_url = "http://localhost:8250/oidc/callback";
    let auth_url_response = vaultrs::auth::oidc::auth(
        &create_client(&env.vault_address, None)?,
        &env.auth_mount_or_default(),
        callback_url,
        role.clone(),
    )
    .await?;

    let server = start_callback_server(env.clone()).await?;
    let handle = server.handle();
    tokio::spawn(server);

    print_or_open_browser(auth_url_response.auth_url).await;

    tokio::spawn(async move {
        tokio::signal::ctrl_c().await.unwrap();
        *SHUTDOWN_SIGNAL.lock().await = true;
    });

    let mut tries = 0;
    loop {
        let auth_response: tokio::sync::MutexGuard<'_, Option<VaultAuthResponse>> =
            AUTH_RESPONSE.lock().await;

        let shutdown_signal = SHUTDOWN_SIGNAL.lock().await;
        if *shutdown_signal {
            handle.stop(true).await;
            return Err(CliError::AuthError(Console::error("canceled")));
        }

        match auth_response.as_ref() {
            Some(auth) => {
                println!("{}", Console::success("token received"));
                let mut config = load_or_create_config().await?;
                write_token(
                    &mut config,
                    &env,
                    &auth.auth.client_token,
                    auth.auth.lease_duration,
                )
                .await?;
                return Ok(auth.clone());
            }
            None => (),
        }
        tokio::time::sleep(tokio::time::Duration::from_secs(1)).await;
        tries += 1;

        if (tries % 10) == 0 {
            println!(
                "{}",
                Console::highlight(format!("waiting for callback for {tries} seconds"))
            );
        }

        if tries > 60 {
            println!("{}", Console::error("timeout waiting for callback"));
            break;
        }
    }

    handle.stop(true).await;
    Err(CliError::AuthError(Console::error("authentication failed")))
}
