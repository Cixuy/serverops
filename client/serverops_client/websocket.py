from __future__ import annotations

import json
import threading
import time
import uuid
from collections.abc import Callable
from typing import Any
from urllib.parse import urlencode, urlsplit, urlunsplit

from websockets.exceptions import WebSocketException
from websockets.sync.client import connect

from .config import AgentConfig
from .models import MonitorConfig


def client_websocket_url(config: AgentConfig, server_code: str) -> str:
    parsed = urlsplit(config.server_url)
    scheme = "wss" if parsed.scheme == "https" else "ws"
    root_path = parsed.path.rstrip("/").removesuffix("/api/v1")
    query = urlencode({"apiKey": config.agent_token, "serverCode": server_code})
    return urlunsplit((scheme, parsed.netloc, f"{root_path}/api/v1/ws/client", query, ""))


def pull_monitor_config_message(server_code: str, request_id: str | None = None) -> str:
    return json.dumps(
        {
            "action": "PULL_MONITOR_CONFIG",
            "request_id": request_id or str(uuid.uuid4()),
            "payload": {"server_code": server_code},
        },
        separators=(",", ":"),
    )


class ConfigWebSocketClient:
    def __init__(
        self,
        config: AgentConfig,
        server_code: str,
        on_configs: Callable[[list[MonitorConfig]], None],
        on_status: Callable[[str, str], None] | None = None,
        connect_factory: Callable[..., Any] = connect,
    ) -> None:
        self.config = config
        self.server_code = server_code
        self.on_configs = on_configs
        self.on_status = on_status or (lambda _kind, _message: None)
        self.connect_factory = connect_factory
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._connection: Any | None = None
        self._connection_lock = threading.Lock()

    def start(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop.clear()
        self._thread = threading.Thread(
            target=self._run, name="serverops-config-ws", daemon=True
        )
        self._thread.start()

    def stop(self, timeout: float = 5.0) -> None:
        self._stop.set()
        with self._connection_lock:
            connection = self._connection
        if connection is not None:
            try:
                connection.close()
            except OSError:
                pass
        if self._thread and self._thread is not threading.current_thread():
            self._thread.join(timeout)

    def _run(self) -> None:
        retry_delay = 1.0
        while not self._stop.is_set():
            connection = None
            try:
                connection = self.connect_factory(
                    client_websocket_url(self.config, self.server_code),
                    open_timeout=self.config.request_timeout,
                    ping_interval=20,
                    ping_timeout=20,
                )
                with self._connection_lock:
                    self._connection = connection
                retry_delay = 1.0
                self.on_status("websocket_connected", "配置 WebSocket 已连接")
                self._serve(connection)
            except (OSError, WebSocketException, TimeoutError) as exc:
                if not self._stop.is_set():
                    self.on_status("websocket_error", f"配置 WebSocket 断开：{exc}")
            finally:
                with self._connection_lock:
                    self._connection = None
                if connection is not None:
                    try:
                        connection.close()
                    except OSError:
                        pass
            if self._stop.wait(retry_delay):
                break
            retry_delay = min(retry_delay * 2, 30.0)

    def _serve(self, connection: Any) -> None:
        self._send_pull(connection)
        next_ping = time.monotonic() + 30
        while not self._stop.is_set():
            if time.monotonic() >= next_ping:
                connection.send(
                    json.dumps(
                        {"action": "ping", "request_id": str(uuid.uuid4())},
                        separators=(",", ":"),
                    )
                )
                next_ping = time.monotonic() + 30
            try:
                raw_message = connection.recv(timeout=1)
            except TimeoutError:
                continue
            self._handle_message(connection, raw_message)

    def _handle_message(self, connection: Any, raw_message: str | bytes) -> None:
        try:
            message = json.loads(raw_message)
        except (TypeError, ValueError):
            self.on_status("websocket_error", "配置 WebSocket 收到无效 JSON")
            return
        if not isinstance(message, dict):
            self.on_status("websocket_error", "配置 WebSocket 消息不是对象")
            return
        action = message.get("action")
        if action == "PULL_MONITOR_CONFIG_RESP":
            data = message.get("data")
            if not isinstance(data, list):
                self.on_status("websocket_error", "WebSocket 配置响应的 data 不是数组")
                return
            configs = [MonitorConfig.from_payload(item) for item in data if isinstance(item, dict)]
            self.on_configs(configs)
        elif action == "MONITOR_CONFIG_CHANGED":
            if message.get("server_code") in {None, self.server_code}:
                self._send_pull(connection)
        elif action == "HEALING_RULE_BINDING_CHANGE":
            self.on_status("healing_rules_changed", "自愈规则已变更，正在重新检查")
        elif action == "pong":
            return
        elif action == "error":
            self.on_status(
                "websocket_error",
                str(message.get("message") or message.get("code") or "WebSocket 请求失败"),
            )

    def _send_pull(self, connection: Any) -> None:
        connection.send(pull_monitor_config_message(self.server_code))
