use crate::{
    config::ProxyConfig,
    model::{ProxySnapshot, ProxyState},
};
use anyhow::{Context, Result, bail};
use reqwest::Client;
use std::{fs, path::Path, time::Duration};
use tokio::{sync::Mutex, task::JoinSet, time::Instant};
use url::Url;

#[derive(Clone)]
pub struct ProxyRoute {
    pub client: Client,
    pub display: String,
    pub index: usize,
}

struct ProxyEndpoint {
    display: String,
    client: Client,
    state: ProxyState,
    latency_ms: Option<u64>,
    last_error: Option<String>,
}

pub struct ProxyPool {
    endpoints: Mutex<Vec<ProxyEndpoint>>,
}

impl ProxyPool {
    pub fn from_file(root: &Path, config: &ProxyConfig, timeout: Duration) -> Result<Self> {
        let path = root.join(&config.file);
        if !path.exists() {
            fs::write(&path, "").with_context(|| format!("failed to create {}", path.display()))?;
        }

        let mut endpoints = Vec::new();
        for raw in fs::read_to_string(&path)
            .with_context(|| format!("failed to read {}", path.display()))?
            .lines()
        {
            let value = raw.trim();
            if value.is_empty() || value.starts_with('#') {
                continue;
            }
            let Ok(url) = normalize_proxy_url(value) else {
                continue;
            };
            let display = redact_proxy_url(&url);
            let proxy =
                reqwest::Proxy::all(&url).with_context(|| format!("invalid proxy {display}"))?;
            let client = Client::builder()
                .proxy(proxy)
                .timeout(timeout)
                .user_agent("Nym/0.2")
                .build()
                .with_context(|| format!("failed to build client for {display}"))?;
            endpoints.push(ProxyEndpoint {
                display,
                client,
                state: ProxyState::Ready,
                latency_ms: None,
                last_error: None,
            });
        }
        Ok(Self {
            endpoints: Mutex::new(endpoints),
        })
    }

    pub async fn len(&self) -> usize {
        self.endpoints.lock().await.len()
    }

    pub async fn is_empty(&self) -> bool {
        self.endpoints.lock().await.is_empty()
    }

    pub async fn preflight(&self) {
        let targets: Vec<(usize, Client)> = {
            let endpoints = self.endpoints.lock().await;
            endpoints
                .iter()
                .enumerate()
                .map(|(index, endpoint)| (index, endpoint.client.clone()))
                .collect()
        };

        let mut set = JoinSet::new();
        for (index, client) in targets {
            set.spawn(async move {
                let started = Instant::now();
                let result = client
                    .get("https://discord.com/api/v9/gateway")
                    .send()
                    .await;
                (index, started.elapsed(), result)
            });
        }

        while let Some(joined) = set.join_next().await {
            let Ok((index, latency, result)) = joined else {
                continue;
            };
            let mut endpoints = self.endpoints.lock().await;
            let Some(endpoint) = endpoints.get_mut(index) else {
                continue;
            };
            match result {
                Ok(response) if response.status().is_success() => {
                    endpoint.state = ProxyState::Ready;
                    endpoint.latency_ms =
                        Some(latency.as_millis().min(u128::from(u64::MAX)) as u64);
                    endpoint.last_error = None;
                }
                Ok(response) => {
                    endpoint.state = ProxyState::Degraded;
                    endpoint.last_error = Some(format!("preflight HTTP {}", response.status()));
                }
                Err(error) => {
                    endpoint.state = ProxyState::Dead;
                    endpoint.last_error = Some(if error.is_timeout() {
                        "ReadTimeout".into()
                    } else {
                        error.to_string()
                    });
                }
            }
        }
    }

    pub async fn select_route(&self) -> Option<ProxyRoute> {
        let endpoints = self.endpoints.lock().await;
        endpoints
            .iter()
            .enumerate()
            .filter(|(_, endpoint)| endpoint.state == ProxyState::Ready)
            .min_by_key(|(_, endpoint)| endpoint.latency_ms.unwrap_or(u64::MAX))
            .map(|(index, endpoint)| ProxyRoute {
                client: endpoint.client.clone(),
                display: endpoint.display.clone(),
                index,
            })
    }

    pub async fn mark_active(&self, route: &ProxyRoute) {
        let mut endpoints = self.endpoints.lock().await;
        if let Some(endpoint) = endpoints.get_mut(route.index) {
            endpoint.state = ProxyState::Active;
        }
    }

    pub async fn mark_ready(&self, route: &ProxyRoute, latency: Duration) {
        let mut endpoints = self.endpoints.lock().await;
        if let Some(endpoint) = endpoints.get_mut(route.index) {
            endpoint.state = ProxyState::Ready;
            endpoint.latency_ms =
                Some(latency.as_millis().min(u128::from(u64::MAX)) as u64);
            endpoint.last_error = None;
        }
    }

    pub async fn mark_error(&self, route: &ProxyRoute, error: impl Into<String>) {
        let mut endpoints = self.endpoints.lock().await;
        if let Some(endpoint) = endpoints.get_mut(route.index) {
            endpoint.state = ProxyState::Degraded;
            endpoint.last_error = Some(error.into());
        }
    }

    pub async fn snapshots(&self) -> Vec<ProxySnapshot> {
        let endpoints = self.endpoints.lock().await;
        endpoints
            .iter()
            .enumerate()
            .map(|(index, endpoint)| ProxySnapshot {
                id: index + 1,
                display: endpoint.display.clone(),
                state: endpoint.state,
                latency_ms: endpoint.latency_ms,
                cooldown_remaining: None,
                worker_id: None,
                last_error: endpoint.last_error.clone(),
            })
            .collect()
    }
}

fn normalize_proxy_url(raw: &str) -> Result<String> {
    let value = raw.trim();
    if value.is_empty() {
        bail!("proxy value is empty");
    }

    let url = if !value.contains("://") && value.matches(':').count() == 3 {
        let parts: Vec<&str> = value.splitn(4, ':').collect();
        let mut url = Url::parse(&format!("http://{}:{}", parts[0], parts[1]))
            .context("invalid proxy host or port")?;
        url.set_username(parts[2])
            .map_err(|_| anyhow::anyhow!("invalid proxy username"))?;
        url.set_password(Some(parts[3]))
            .map_err(|_| anyhow::anyhow!("invalid proxy password"))?;
        url
    } else {
        let normalized = if value.contains("://") {
            value.to_owned()
        } else {
            format!("http://{value}")
        };
        Url::parse(&normalized).context("invalid proxy URL")?
    };

    if !matches!(url.scheme(), "http" | "https" | "socks5") {
        bail!("unsupported proxy scheme: {}", url.scheme());
    }
    if url.host_str().is_none() || url.port().is_none() {
        bail!("proxy host or port is missing");
    }
    Ok(url.to_string())
}

fn redact_proxy_url(raw: &str) -> String {
    let Ok(mut url) = Url::parse(raw) else {
        return "<invalid proxy>".into();
    };
    if !url.username().is_empty() {
        let _ = url.set_username("***");
        let _ = url.set_password(Some("***"));
    }
    url.to_string().trim_end_matches('/').to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn credentials_are_redacted() {
        let normalized = normalize_proxy_url("127.0.0.1:8080:user:pass").unwrap();
        let display = redact_proxy_url(&normalized);
        assert!(display.contains("***:***@127.0.0.1:8080"));
        assert!(!display.contains("user"));
        assert!(!display.contains("pass"));
    }
}
