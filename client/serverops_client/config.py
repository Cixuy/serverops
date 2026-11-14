from __future__ import annotations

import json
import os
import socket
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit


def application_data_dir() -> Path:
    if os.name == "nt":
        root = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData" / "Local"))
    else:
        root = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    return root / "ServerOpsAgent"


@dataclass(slots=True)
class AgentConfig:
    server_url: str = "http://127.0.0.1:8080"
    agent_token: str = ""
    sample_interval: float = 5.0
    heartbeat_interval: float = 30.0
    request_timeout: float = 10.0
    queue_limit: int = 10_000
    alert_cooldown_minutes: int = 30
    healing_timeout_recheck_delay: float = 5.0

    def validate(self) -> None:
        parsed = urlsplit(self.server_url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            raise ValueError("服务器地址必须以 http:// 或 https:// 开头")
        try:
            _port = parsed.port
        except ValueError as exc:
            raise ValueError("服务器地址端口无效") from exc
        if not self.agent_token.strip():
            raise ValueError("Agent Token 不能为空")
        if self.sample_interval < 1:
            raise ValueError("采样周期不能小于 1 秒")
        if self.heartbeat_interval < 5:
            raise ValueError("心跳周期不能小于 5 秒")
        if self.request_timeout < 1:
            raise ValueError("请求超时不能小于 1 秒")
        if self.alert_cooldown_minutes < 1:
            raise ValueError("告警冷却不能小于 1 分钟")
        if self.healing_timeout_recheck_delay < 0:
            raise ValueError("自愈超时复检延时不能小于 0 秒")

    def resolve_server_address(self) -> None:
        parsed = urlsplit(self.server_url)
        host = parsed.hostname
        if not host:
            raise ValueError("服务器地址无效")
        port = parsed.port or (443 if parsed.scheme == "https" else 80)
        try:
            socket.getaddrinfo(host, port, type=socket.SOCK_STREAM)
        except socket.gaierror as exc:
            raise ValueError(f"无法解析服务器地址：{host}") from exc


class ConfigStore:
    def __init__(self, data_dir: Path | None = None) -> None:
        self.data_dir = data_dir or application_data_dir()
        self.config_path = self.data_dir / "config.json"
        self.state_path = self.data_dir / "state.json"

    def load(self) -> AgentConfig:
        values: dict[str, Any] = {}
        if self.config_path.exists():
            values = json.loads(self.config_path.read_text(encoding="utf-8"))

        env_map: dict[str, tuple[str, type]] = {
            "SERVEROPS_SERVER_URL": ("server_url", str),
            "SERVEROPS_AGENT_TOKEN": ("agent_token", str),
            "SERVEROPS_SAMPLE_INTERVAL": ("sample_interval", float),
            "SERVEROPS_HEARTBEAT_INTERVAL": ("heartbeat_interval", float),
            "SERVEROPS_REQUEST_TIMEOUT": ("request_timeout", float),
            "SERVEROPS_ALERT_COOLDOWN_MINUTES": ("alert_cooldown_minutes", int),
            "SERVEROPS_HEALING_TIMEOUT_RECHECK_DELAY": (
                "healing_timeout_recheck_delay",
                float,
            ),
        }
        for env_name, (field_name, converter) in env_map.items():
            if env_name in os.environ:
                values[field_name] = converter(os.environ[env_name])
        allowed = AgentConfig.__dataclass_fields__.keys()
        return AgentConfig(**{key: value for key, value in values.items() if key in allowed})

    def save(self, config: AgentConfig) -> None:
        config.validate()
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.config_path.write_text(
            json.dumps(asdict(config), ensure_ascii=False, indent=2), encoding="utf-8"
        )
        if os.name != "nt":
            self.config_path.chmod(0o600)

    def load_server_code(self) -> str | None:
        state = self._load_state()
        value = state.get("serverCode")
        return value if isinstance(value, str) and value else None

    def save_server_code(self, server_code: str) -> None:
        state = self._load_state()
        state["serverCode"] = server_code
        self._save_state(state)

    def load_alert_report_times(self) -> dict[int, float]:
        values = self._load_state().get("alertReportTimes")
        if not isinstance(values, dict):
            return {}
        result: dict[int, float] = {}
        for config_id, timestamp in values.items():
            try:
                result[int(config_id)] = float(timestamp)
            except (TypeError, ValueError):
                continue
        return result

    def save_alert_report_time(self, config_id: int, timestamp: float) -> None:
        state = self._load_state()
        values = state.setdefault("alertReportTimes", {})
        if not isinstance(values, dict):
            values = {}
            state["alertReportTimes"] = values
        values[str(config_id)] = timestamp
        self._save_state(state)

    def _load_state(self) -> dict[str, Any]:
        if not self.state_path.exists():
            return {}
        state = json.loads(self.state_path.read_text(encoding="utf-8"))
        return state if isinstance(state, dict) else {}

    def _save_state(self, state: dict[str, Any]) -> None:
        self.data_dir.mkdir(parents=True, exist_ok=True)
        self.state_path.write_text(
            json.dumps(state, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
