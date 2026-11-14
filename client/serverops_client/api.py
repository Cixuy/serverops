from __future__ import annotations

import json
from typing import Any, Self

import httpx

from .config import AgentConfig
from .logging_config import get_logger
from .models import HealingRule, HostInfo, MonitorConfig

logger = get_logger("api")
MAX_PARAMETER_LOG_LENGTH = 2_000
SENSITIVE_KEY_PARTS = ("authorization", "password", "secret", "token", "api_key", "apikey")


class ApiError(RuntimeError):
    pass


class AgentApi:
    def __init__(self, config: AgentConfig, transport: httpx.BaseTransport | None = None) -> None:
        self.config = config
        self._client = httpx.Client(
            timeout=config.request_timeout,
            headers={"X-Agent-Token": config.agent_token, "Accept": "application/json"},
            transport=transport,
        )

    def close(self) -> None:
        self._client.close()

    def register(self, host: HostInfo) -> str:
        path = "/api/v1/servers/register"
        payload = host.to_register_payload()
        result = self._post(path, payload)
        data = result.get("data")
        server_code = data.get("serverCode") if isinstance(data, dict) else None
        if not isinstance(server_code, str) or not server_code:
            raise self._request_error(
                "POST", path, payload, "响应缺少 data.serverCode"
            )
        return server_code

    def heartbeat(self, server_code: str, timestamp: int) -> None:
        self._post(
            "/api/v1/servers/heartbeat",
            {"serverCode": server_code, "timestamp": timestamp},
        )

    def get_monitor_configs(self, server_code: str) -> list[MonitorConfig]:
        path = "/api/v1/agent/monitor-configs"
        params = {"serverCode": server_code}
        result = self._get(path, params)
        data = result.get("data")
        if data is None:
            return []
        if not isinstance(data, list):
            raise self._request_error("GET", path, params, "响应的 data 不是数组")
        return [MonitorConfig.from_payload(item) for item in data if isinstance(item, dict)]

    def report_metrics(self, payload: dict[str, Any]) -> None:
        self._post("/api/v1/metrics", payload)

    def report_service_status(self, payload: dict[str, Any]) -> None:
        self._post("/api/v1/agent/service-status/report", payload)

    def report_alert_event(self, payload: dict[str, Any]) -> int | None:
        result = self._post("/api/v1/alert-events", payload, success_codes={0, 200})
        data = result.get("data")
        alert_id = data.get("id") if isinstance(data, dict) else None
        return alert_id if isinstance(alert_id, int) and not isinstance(alert_id, bool) else None

    def get_bound_healing_rules(
        self, server_code: str, config_id: int
    ) -> list[HealingRule]:
        result = self._get(
            "/api/v1/service-healing-bindings",
            {"serverCode": server_code, "configId": config_id},
            success_codes={0, 200},
        )
        data = result.get("data")
        items = data.get("list") if isinstance(data, dict) else data
        if items is None:
            return []
        if not isinstance(items, list):
            raise self._request_error(
                "GET",
                "/api/v1/service-healing-bindings",
                {"serverCode": server_code, "configId": config_id},
                "响应的 data.list 不是数组",
            )
        return [
            HealingRule.from_payload(item)
            for item in items
            if isinstance(item, dict) and item.get("status", 1) != 0
        ]

    def report_healing_log(self, payload: dict[str, Any]) -> None:
        self._post("/api/v1/healing-action-logs", payload, success_codes={0, 200})

    def _post(
        self,
        path: str,
        payload: dict[str, Any],
        success_codes: set[int] | None = None,
    ) -> dict[str, Any]:
        url = self._url(path)
        parameter_text = _format_parameters(payload)
        logger.debug("POST %s 请求参数=%s", url, parameter_text)
        try:
            response = self._client.post(url, json=payload)
            response.raise_for_status()
            body = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise self._request_error(
                "POST", path, payload, _response_error_reason(exc)
            ) from exc
        if not isinstance(body, dict):
            raise self._request_error("POST", path, payload, "服务端返回了无效响应")
        if body.get("code") not in (success_codes or {0}):
            reason = str(
                body.get("msg")
                or body.get("message")
                or f"服务端业务码 {body.get('code')}"
            )
            raise self._request_error("POST", path, payload, reason)
        logger.debug(
            "POST %s 请求成功，httpStatus=%s，businessCode=%s",
            url,
            response.status_code,
            body.get("code"),
        )
        return body

    def _get(
        self,
        path: str,
        params: dict[str, Any],
        success_codes: set[int] | None = None,
    ) -> dict[str, Any]:
        url = self._url(path)
        request_url = str(httpx.URL(url, params=_sanitize(params)))
        parameter_text = _format_parameters(params)
        logger.debug("GET %s 请求参数=%s", request_url, parameter_text)
        try:
            response = self._client.get(url, params=params)
            response.raise_for_status()
            body = response.json()
        except (httpx.HTTPError, ValueError) as exc:
            raise self._request_error(
                "GET", path, params, _response_error_reason(exc)
            ) from exc
        if not isinstance(body, dict):
            raise self._request_error("GET", path, params, "服务端返回了无效响应")
        if body.get("code") not in (success_codes or {0}):
            reason = str(
                body.get("msg")
                or body.get("message")
                or f"服务端业务码 {body.get('code')}"
            )
            raise self._request_error("GET", path, params, reason)
        logger.debug(
            "GET %s 请求成功，httpStatus=%s，businessCode=%s",
            request_url,
            response.status_code,
            body.get("code"),
        )
        return body

    def _request_error(
        self, method: str, path: str, parameters: Any, reason: str
    ) -> ApiError:
        url = self._url(path)
        if method == "GET" and isinstance(parameters, dict):
            url = str(httpx.URL(url, params=_sanitize(parameters)))
        return ApiError(
            f"请求失败: {method} {url} | 参数: {_format_parameters(parameters)} | 原因: {reason}"
        )

    def _url(self, path: str) -> str:
        root = self.config.server_url.rstrip("/")
        if root.endswith("/api/v1") and path.startswith("/api/v1/"):
            path = path[len("/api/v1") :]
        return f"{root}{path}"

    def __enter__(self) -> Self:
        return self

    def __exit__(self, *_: object) -> None:
        self.close()


def _format_parameters(parameters: Any) -> str:
    sanitized = _sanitize(parameters)
    text = json.dumps(sanitized, ensure_ascii=False, separators=(",", ":"), default=str)
    if len(text) > MAX_PARAMETER_LOG_LENGTH:
        return f"{text[:MAX_PARAMETER_LOG_LENGTH]}...<truncated>"
    return text


def _sanitize(value: Any) -> Any:
    if isinstance(value, dict):
        return {
            str(key): (
                "<redacted>"
                if any(part in str(key).casefold() for part in SENSITIVE_KEY_PARTS)
                else _sanitize(item)
            )
            for key, item in value.items()
        }
    if isinstance(value, (list, tuple)):
        return [_sanitize(item) for item in value]
    return value


def _response_error_reason(exc: httpx.HTTPError | ValueError) -> str:
    reason = str(exc)
    if not isinstance(exc, httpx.HTTPStatusError):
        return reason
    response = exc.response
    try:
        response_detail: Any = response.json()
    except ValueError:
        response_detail = response.text
    if response_detail in (None, "", {}):
        return reason
    return f"{reason} | 响应: {_format_parameters(response_detail)}"
