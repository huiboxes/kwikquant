"""SDK 异常层。

跨语言不接 Java 异常链,SDK 内部映射 HTTP 状态 → Python 异常。
上层用户 catch ``KqApiError`` / ``KqTimeoutError`` 处理。
"""


class KqError(Exception):
    """基类。"""


class KqApiError(KqError):
    """HTTP 4xx/5xx 异常。携带原始 status/code/message,便于用户判断。"""

    def __init__(self, status: int, code: int | None, message: str, response_body: str | None = None):
        self.status = status
        self.code = code
        self.message = message
        self.response_body = response_body
        super().__init__(f"KqApiError(status={status}, code={code}): {message}")


class KqTimeoutError(KqError):
    """HTTP/WS 超时。默认 httpx 30s 触发。"""


class KqAuthError(KqApiError):
    """401(7301 WORKER_TOKEN_INVALID 或 JWT 失效)。"""
