from pathlib import Path

import pytest

from nym.proxy import ProxyPool, normalize_proxy_url, redact_proxy_url


def test_normalize_bare_http_proxy() -> None:
    assert normalize_proxy_url("127.0.0.1:8080") == "http://127.0.0.1:8080"


def test_normalize_host_port_user_password() -> None:
    value = normalize_proxy_url("proxy.example:3128:user:pass")
    assert value == "http://user:pass@proxy.example:3128"


def test_redacts_credentials() -> None:
    value = redact_proxy_url("http://user:secret@proxy.example:8080")
    assert value == "http://***:***@proxy.example:8080"
    assert "secret" not in value


def test_invalid_proxy_port() -> None:
    with pytest.raises(ValueError):
        normalize_proxy_url("proxy.example:99999")


def test_pool_loads_valid_lines_only(tmp_path: Path) -> None:
    path = tmp_path / "proxies.txt"
    path.write_text(
        "127.0.0.1:8080\n# comment\nnot-a-proxy\nsocks5://127.0.0.1:1080\n",
        encoding="utf-8",
    )

    pool = ProxyPool.from_file(path)

    assert len(pool.endpoints) == 2
