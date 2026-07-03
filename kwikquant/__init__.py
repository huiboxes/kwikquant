"""kwikquant SDK — thin Python client for KwikQuant Java API.

外部用户: ``pip install kwikquant`` → ``from kwikquant import Client, Auth``.
Worker(kwikquant-worker): 复用 ``Client(base_url, Auth.service_token(...))``。
"""

from kwikquant.client import Auth, Client
from kwikquant.errors import KqApiError, KqTimeoutError

__all__ = ["Client", "Auth", "KqApiError", "KqTimeoutError"]
__version__ = "0.1.0"
