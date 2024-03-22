use std::collections::HashMap;

use actix_web::{dev::Server, get, web, App, HttpResponse, HttpServer};

use once_cell::sync::Lazy;
use serde::Deserialize;
use tokio::sync::Mutex;
use vaultrs::client::{VaultClient, VaultClientSettingsBuilder};

use crate::{
    config::{get_env, load_or_create_config, write_token, Config, EnvironmentConfig},
    error::CliError,
};

static AUTH_RESPONSE: Lazy<Mutex<Option<VaultAuthResponse>>> = Lazy::new(|| Mutex::new(None));

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
        return Err(CliError::VaultError(format!(
            "Vault returned an error: {}, {}",
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
    .bind(("localhost", 8250))?
    .run())
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

    println!("auth url: {}", auth_url_response.auth_url);

    let mut tries = 0;
    loop {
        println!("waiting for callback for {tries} seconds");
        let auth_response: tokio::sync::MutexGuard<'_, Option<VaultAuthResponse>> =
            AUTH_RESPONSE.lock().await;

        match auth_response.as_ref() {
            Some(auth) => {
                println!("token received");
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

        if tries > 60 {
            println!("timeout waiting for callback");
            break;
        }
    }

    handle.stop(true).await;
    Err(CliError::RuntimeError("Authentication failed".to_string()))
}
