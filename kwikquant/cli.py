"""``kwikquant.cli`` — ``kq`` CLI 薄壳。

单工具 surface：argparse 子命令，零额外依赖（stdlib + 已有 httpx）。
惰性发现：``kq --help`` 列命令组、``kq ticker --help`` 列子命令。
默认裁剪输出，``--full`` 解全量。退出码即 detect 信号（0 成功 / 1 失败）。

配置来源（优先级）：env ``KQ_BASE_URL``/``KQ_TOKEN`` > ``~/.kq/token`` 文件 > 默认 ``http://localhost:8080``。
"""

from __future__ import annotations

import argparse
import getpass
import json
import os
import sys
from pathlib import Path

import httpx

from kwikquant.client import Auth, Client
from kwikquant.errors import KqApiError, KqAuthError

DEFAULT_BASE_URL = "http://localhost:8080"
TOKEN_DIR = Path.home() / ".kq"
TOKEN_FILE = TOKEN_DIR / "token"


def _base_url() -> str:
    return os.environ.get("KQ_BASE_URL", DEFAULT_BASE_URL).rstrip("/")


def _load_token() -> str | None:
    env_token = os.environ.get("KQ_TOKEN")
    if env_token:
        return env_token
    if TOKEN_FILE.exists():
        return TOKEN_FILE.read_text().strip() or None
    return None


def _save_token(token: str) -> None:
    TOKEN_DIR.mkdir(parents=True, exist_ok=True)
    TOKEN_FILE.write_text(token)
    TOKEN_FILE.chmod(0o600)


def _die(msg: str, code: int = 1) -> None:
    print(f"kq: {msg}", file=sys.stderr)
    raise SystemExit(code)


# --- auth ---
def cmd_auth_login(args: argparse.Namespace) -> None:
    base = _base_url()
    password = args.password or getpass.getpass("password: ")
    try:
        resp = httpx.post(
            f"{base}/api/v1/auth/login",
            json={"username": args.username, "password": password},
            timeout=30.0,
        )
    except httpx.RequestError as e:
        _die(f"login request failed: {e}")
        return
    try:
        body = resp.json()
    except ValueError:
        _die(f"login: non-json response (HTTP {resp.status_code})")
        return
    if body.get("code") != 0:
        _die(f"login failed: code={body.get('code')} message={body.get('message')}")
        return
    data = body.get("data") or {}
    token = data.get("accessToken")
    if not token:
        _die("login: no accessToken in response")
        return
    _save_token(token)
    expires_in = data.get("expiresIn", "?")
    print(f"login ok, token expires in {expires_in}s; saved to {TOKEN_FILE}")


# --- ticker ---
def cmd_ticker_get(args: argparse.Namespace) -> None:
    token = _load_token()
    if not token:
        _die("not logged in; run: kq auth login --username <user>")
        return
    client = Client(_base_url(), Auth.jwt(token))
    # 后端 Exchange/MarketType 枚举大小写敏感，规范化大写
    exchange = args.exchange.upper()
    market_type = args.market_type.upper()
    try:
        data = client.data.ticker(exchange, market_type, args.symbol)
    except KqAuthError as e:
        _die(f"auth failed (token expired? login again): {e}")
        return
    except KqApiError as e:
        _die(f"ticker fetch failed: {e}")
        return

    if not isinstance(data, dict):
        _die(f"unexpected ticker payload: {data!r}")
        return

    if args.full:
        print(json.dumps(data, indent=2, default=str))
        return

    ticker = data.get("ticker") or {}
    last = ticker.get("last")
    bid = ticker.get("bid")
    ask = ticker.get("ask")
    stale = data.get("stale")
    print(f"{args.symbol} last={last} bid={bid} ask={ask} stale={stale}")


def _build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="kq",
        description="KwikQuant CLI 薄壳（单工具 + 惰性发现 + 输出裁剪）。",
    )
    sub = parser.add_subparsers(dest="command", required=True, metavar="<command>")

    # kq auth ...
    auth = sub.add_parser("auth", help="登录与凭据管理").add_subparsers(
        dest="auth_command", required=True, metavar="<subcommand>"
    )
    login = auth.add_parser("login", help="POST /api/v1/auth/login，存 token 到 ~/.kq/token")
    login.add_argument("--username", "-u", required=True)
    login.add_argument("--password", "-p", help="不传则交互输入（不回显）")
    login.set_defaults(func=cmd_auth_login)

    # kq ticker ...
    ticker = sub.add_parser("ticker", help="行情查询").add_subparsers(
        dest="ticker_command", required=True, metavar="<subcommand>"
    )
    get = ticker.add_parser("get", help="GET /api/v1/market/ticker/{ex}/{mt}/{sym}")
    get.add_argument("-e", "--exchange", required=True, help="binance/okx/bitget/...")
    get.add_argument("-m", "--market-type", required=True, help="spot/perp")
    get.add_argument("-s", "--symbol", required=True, help="BTC/USDT（/ 在 URL 自动转 -）")
    get.add_argument("--full", action="store_true", help="输出全量字段（默认裁剪一行）")
    get.set_defaults(func=cmd_ticker_get)

    return parser


def main() -> None:
    parser = _build_parser()
    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
