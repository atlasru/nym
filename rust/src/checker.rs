use crate::{
    model::{CheckResult, CheckStatus},
    proxy::{ProxyPool, ProxyRoute},
};
use anyhow::{Context, Result};
use reqwest::{Client, StatusCode};
use serde_json::Value;
use std::{sync::Arc, time::Duration};
use tokio::time::Instant;

pub const DEFAULT_ENDPOINT: &str =
    "https://discord.com/api/v9/unique-username/username-attempt-unauthed";

#[derive(Clone)]
pub struct UsernameChecker {
    direct_client: Client,
    proxy_pool: Option<Arc<ProxyPool>>,
    route: Option<ProxyRoute>,
    endpoint: String,
}

impl UsernameChecker {
    pub fn new(
        timeout: Duration,
        proxy_pool: Option<Arc<ProxyPool>>,
        route: Option<ProxyRoute>,
    ) -> Result<Self> {
        let direct_client = Client::builder()
            .timeout(timeout)
            .user_agent("Nym/0.2")
            .build()
            .context("failed to build HTTP client")?;
        Ok(Self {
            direct_client,
            proxy_pool,
            route,
            endpoint: DEFAULT_ENDPOINT.into(),
        })
    }

    pub async fn check(&self, username: &str) -> CheckResult {
        let (client, proxy_display) = if let Some(route) = &self.route {
            if let Some(pool) = &self.proxy_pool {
                pool.mark_active(route).await;
            }
            (route.client.clone(), Some(route.display.clone()))
        } else {
            (self.direct_client.clone(), None)
        };

        let started = Instant::now();
        let response = client
            .post(&self.endpoint)
            .json(&serde_json::json!({ "username": username }))
            .send()
            .await;

        let response = match response {
            Ok(response) => response,
            Err(error) => {
                if let (Some(pool), Some(route)) = (&self.proxy_pool, &self.route) {
                    pool.mark_error(route, classify_network_error(&error)).await;
                }
                let mut result = CheckResult::new(username, CheckStatus::NetworkError);
                result.error = Some(classify_network_error(&error));
                result.proxy = proxy_display;
                return result;
            }
        };

        let latency = started.elapsed();
        let status = response.status();
        let headers = response.headers().clone();
        let body = response.text().await.unwrap_or_default();

        if let (Some(pool), Some(route)) = (&self.proxy_pool, &self.route) {
            if status == StatusCode::TOO_MANY_REQUESTS {
                pool.mark_ready(route, latency).await;
            } else if status.is_server_error() {
                pool.mark_error(route, format!("HTTP {}", status.as_u16())).await;
            } else {
                pool.mark_ready(route, latency).await;
            }
        }

        if status == StatusCode::TOO_MANY_REQUESTS {
            let mut result = CheckResult::new(username, CheckStatus::RateLimited);
            result.http_status = Some(status.as_u16());
            result.retry_after = retry_after_seconds(&headers, &body);
            result.error = rate_limit_detail(&headers, &body, proxy_display.as_deref());
            result.proxy = proxy_display;
            return result;
        }

        if matches!(status.as_u16(), 400 | 422) {
            let mut result = CheckResult::new(username, CheckStatus::Invalid);
            result.http_status = Some(status.as_u16());
            result.proxy = proxy_display;
            return result;
        }

        if !status.is_success() {
            let mut result = CheckResult::new(username, CheckStatus::Unknown);
            result.http_status = Some(status.as_u16());
            let preview: String = body.chars().take(256).collect();
            result.error = Some(format!("HTTP {}: {preview}", status.as_u16()));
            result.proxy = proxy_display;
            return result;
        }

        let payload: Value = match serde_json::from_str(&body) {
            Ok(value) => value,
            Err(error) => {
                let mut result = CheckResult::new(username, CheckStatus::Unknown);
                result.http_status = Some(status.as_u16());
                result.error = Some(format!("invalid JSON: {error}"));
                result.proxy = proxy_display;
                return result;
            }
        };

        let check_status = match payload.get("taken").and_then(Value::as_bool) {
            Some(true) => CheckStatus::Taken,
            Some(false) => CheckStatus::Available,
            None => CheckStatus::Unknown,
        };
        let mut result = CheckResult::new(username, check_status);
        result.http_status = Some(status.as_u16());
        result.proxy = proxy_display;
        result
    }
}

fn retry_after_seconds(headers: &reqwest::header::HeaderMap, body: &str) -> Option<f64> {
    if let Some(value) = headers.get(reqwest::header::RETRY_AFTER)
        && let Ok(text) = value.to_str()
        && let Ok(seconds) = text.parse::<f64>()
    {
        return Some(seconds.max(0.0));
    }

    serde_json::from_str::<Value>(body)
        .ok()
        .and_then(|value| value.get("retry_after").and_then(Value::as_f64))
        .map(|seconds| seconds.max(0.0))
}

fn rate_limit_detail(
    headers: &reqwest::header::HeaderMap,
    body: &str,
    proxy: Option<&str>,
) -> Option<String> {
    let mut details = Vec::new();
    if let Some(proxy) = proxy {
        details.push(proxy.to_owned());
    }
    if let Some(scope) = headers
        .get("x-ratelimit-scope")
        .and_then(|value| value.to_str().ok())
    {
        details.push(format!("scope={scope}"));
    }
    let global_header = headers
        .get("x-ratelimit-global")
        .and_then(|value| value.to_str().ok())
        .is_some_and(|value| value.eq_ignore_ascii_case("true"));
    let global_body = serde_json::from_str::<Value>(body)
        .ok()
        .and_then(|value| value.get("global").and_then(Value::as_bool))
        .unwrap_or(false);
    if global_header || global_body {
        details.push("global=true".into());
    }
    (!details.is_empty()).then(|| details.join(" · "))
}

fn classify_network_error(error: &reqwest::Error) -> String {
    if error.is_timeout() {
        "ReadTimeout".into()
    } else if error.is_connect() {
        format!("ConnectError: {error}")
    } else {
        format!("NetworkError: {error}")
    }
}
