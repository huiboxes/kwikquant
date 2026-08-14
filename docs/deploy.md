# KwikQuant 部署手册

> tag 发版:`git tag v1.2.3 && git push origin v1.2.3` → GitHub Actions build 三镜像(app/worker/frontend)→ push GHCR → 服务器 `server-deploy-image.sh` docker compose pull。
> 生产服务器只拉镜像,不编译(告别 self-build)。硅谷服务器,OKX 直连,无 CCXT 代理。

---

## 1. 架构

```
              ┌──────────────────────────────────────────────────┐
              │                硅谷云服务器(Linux)                │
              │                                                   │
              │  CF Full strict → 源站 :443 TLS(三选一,见 5.6)    │
              │                                                   │
              │  A. 宝塔(裸 nginx 宿主):    :443+cert → 127.0.0.1:8081
              │  B. 1Panel(OpenResty 容器): :443+cert → 172.17.0.1:8081
              │  C. 无面板(edge 容器 profile)::443+origin cert →
              │                               kwikquant-frontend:80
              │                                                   │
              │  kwikquant-frontend(nginx,0.0.0.0:8081→容器80)    │
              │   ├─ / → SPA dist(镜像内置,不用 scp)             │
              │   ├─ /api/* → kwikquant-app:8080                │
              │   └─ /ws  → kwikquant-app:8080 (STOMP)          │
              │                                                   │
              │  kwikquant-app(Java 21,127.0.0.1:8080)            │
              │   ├─ PostgreSQL(kwikquant-postgres)                │
              │   └─ DockerWorkerManager 按需 docker run         │
              │        strategy-worker-{id} (Python)            │
              │        加入 kwikquant-worker-net                 │
              └──────────────────────────────────────────────────┘
```

- **三容器编排**:`docker/docker-compose.prod.yml`(postgres + app + frontend,共享 `kwikquant-worker-net` bridge 网络)+ 可选 edge 容器(profile `edge`)
- **frontend 容器 `0.0.0.0:8081`**:非标准端口不冲突面板 nginx :80/:443;绑 0.0.0.0 兼容器化面板(1Panel OpenResty 经 172.17.0.1 访问)。serve SPA + 反代 app。防火墙挡 8081 公网(见 5.7 节)
- **edge 容器(可选 profile)**:无面板用户的 TLS 终结层,nginx :443 + 挂 CF Origin Certificate → `http://kwikquant-frontend:80`(同 worker-net 容器名)。有面板用户不起 edge
- **worker 不长驻**:`DockerWorkerManager`(app 容器内)按策略 `docker run --network kwikquant-worker-net` 起 `strategy-worker-{id}` 容器,复用同网络访问 `app:8080`。token 运行时由 `WorkerTokenService` 签发注入,不预置。
- **镜像**:GHCR 私仓 `ghcr.io/huiboxes/kwikquant[-worker|-frontend]:<tag>`
- **profile**:`SPRING_PROFILES_ACTIVE=prod` 激活 `application-prod.yaml`(容器名直连、worker 镜像 GHCR、日志降级、cookie.secure=true)

---

## 2. .env 清单

服务器 `/opt/kwikquant/.env`(`env_file` 注入 app 容器)。复制 `.env.example` 改值。**所有 secret 用 `openssl rand -base64 32` 生成,不要提交 .env 到 git**。

| 变量 | 必填 | 说明 |
|---|---|---|
| `POSTGRES_HOST` | ✅ | `kwikquant-postgres`(容器名;compose environment 已覆盖) |
| `POSTGRES_PORT` | ✅ | `5432` |
| `POSTGRES_DB` | ✅ | `kwikquant` |
| `POSTGRES_USER` | ✅ | `kwikquant` |
| `POSTGRES_PASSWORD` | ✅ | `openssl rand -base64 24` |
| `JWT_SECRET` | ✅ | `openssl rand -base64 32`(不可变) |
| `ENCRYPTION_KEY` | ✅ | `openssl rand -base64 32`(不可变;改了已存 API key 解密失败) |
| `KWIKQUANT_MCP_PEPPER` | ✅ | `openssl rand -base64 32`(不可变;改了已签 PAT 失效) |
| `SPRING_PROFILES_ACTIVE` | ✅ | `prod` |
| `KWIKQUANT_WORKER_PYTHON` | ⚠️ | 可选;**仅 `runner=subprocess`(dev/test)用**。prod `runner=docker` 回测在隔离容器内跑(复用 worker 镜像),不消费此变量 |
| `KWIKQUANT_WORKER_IMAGE` | ⚠️ | 可选;worker/回测容器镜像,默认 `ghcr.io/huiboxes/kwikquant-worker:latest`(deploy 脚本锁 tag) |

> 硅谷服务器 OKX 直连,**不需要** `HTTP_PROXY`/`HTTPS_PROXY`/CCXT 代理(`application.yaml` 不写 `proxy.defaults` → `ProxyProperties.resolve` 返回直连)。

### 生产 .env 模板

```bash
# /opt/kwikquant/.env
POSTGRES_HOST=kwikquant-postgres
POSTGRES_PORT=5432
POSTGRES_DB=kwikquant
POSTGRES_USER=kwikquant
POSTGRES_PASSWORD=<openssl rand -base64 24>
JWT_SECRET=<openssl rand -base64 32>
ENCRYPTION_KEY=<openssl rand -base64 32>
KWIKQUANT_MCP_PEPPER=<openssl rand -base64 32>
SPRING_PROFILES_ACTIVE=prod
# prod 回测在隔离容器执行(kwikquant.backtest.runner=docker),无需 python 子进程变量。
# KWIKQUANT_WORKER_IMAGE=ghcr.io/huiboxes/kwikquant-worker:latest   # 可选;worker/回测容器镜像
```

> ⚠️ `ENCRYPTION_KEY` / `KWIKQUANT_MCP_PEPPER` / `JWT_SECRET` **不可变**——改了等于重置(已加密 API key / PAT / refresh token 全失效)。生产前一次生成,妥善备份。

---

## 3. 生产 profile

`src/main/resources/application-prod.yaml`(已入库)。关键覆盖:

| 配置 | 值 | 说明 |
|---|---|---|
| `kwikquant.worker.api-base-url` | `http://kwikquant-app:8080` | runner worker 容器访问同网络 app 容器名 |
| `kwikquant.worker.image` | `ghcr.io/huiboxes/kwikquant-worker:latest` | DockerWorkerManager docker run 用 |
| `kwikquant.cookie.secure` | `true` | CF/面板 nginx 已终结 TLS,Secure cookie 经反代转发,浏览器正常发回 |
| `logging.level` | INFO/WARN | 降日志噪音 |

激活:`docker-compose.prod.yml` 的 app `environment: SPRING_PROFILES_ACTIVE: prod`。

---

## 4. CI 镜像发布

`.github/workflows/docker-publish.yml` 触发 `push tags v*`:

```bash
# 本地:确保 main 分支 ci.yml 测试绿后,打 tag 发版
git tag v1.2.3
git push origin v1.2.3
# → Actions 构建 app/worker/frontend 三镜像 → push GHCR:
#   ghcr.io/huiboxes/kwikquant:v1.2.3 + :latest
#   ghcr.io/huiboxes/kwikquant-worker:v1.2.3 + :latest
#   ghcr.io/huiboxes/kwikquant-frontend:v1.2.3 + :latest
```

GHCR 用默认 `GITHUB_TOKEN`(`packages:write` 自带),不用额外 secret。镜像私仓,服务器需 `docker login ghcr.io`(见 5.2 节)。

> **流程约束**:本 workflow 只 build 不跑测试(Dockerfile 内 `mvn package -DskipTests`);测试由 push main / PR 的 `ci.yml` 守。打 tag 前确保 main 测试绿。
>
> **worker 镜像**用 `docker/kwikquant-worker.Dockerfile`(完整安全加固版:uid 1000 对齐 `DockerWorkerManager --user 1000:1000`、装 libpq5、stdlib HEALTHCHECK、`--read-only` 兼容)。**不要**用 `docker/Dockerfile.worker`(已删,简化版 uid 999 会导致 `--user 1000:1000` 失败)。

---

## 5. 服务器首次部署

### 5.1 前置

服务器需有 docker + docker compose plugin + git + curl。**已有 docker 的服务器跳过安装**(1Panel/宝塔自带或用户已装):

```bash
docker --version || sudo apt update && sudo apt install -y docker.io docker-compose-plugin   # 已有跳过
git --version || sudo apt install -y git
sudo systemctl enable --now docker
sudo usermod -aG docker $USER && newgrp docker   # 当前用户加 docker 组(deploy 脚本 getent group docker 读 gid)
```

> **worker 编排依赖**:app 容器挂 `/var/run/docker.sock` + `group_add DOCKER_GID`(deploy 脚本自动检测宿主 docker 组 gid)编排 worker(DockerWorkerManager docker run)。这是 DooD(Docker-out-of-Docker)模式,app 容器能控制宿主 docker daemon——**app 是特权边界,被攻破可逃逸宿主 docker**(单机生产可接受,加固 app 镜像 + 最小权限)。已知限制:多机/隔离部署需独立 orchestrator。

### 5.2 docker login GHCR(私仓拉取)

GitHub → Settings → Developer settings → Personal access tokens → 生成 PAT(`read:packages`),服务器:

```bash
echo "<PAT>" | docker login ghcr.io -u huiboxes --password-stdin
```

### 5.3 建部署目录 + .env

```bash
sudo mkdir -p /opt/kwikquant && sudo chown $USER:$USER /opt/kwikquant
nano /opt/kwikquant/.env      # 按第 2 节填(用 openssl rand 生成 secret)
```

### 5.4 拉代码(deploy 脚本首次会自动 clone;或手动)

```bash
git clone https://github.com/huiboxes/kwikquant.git /opt/kwikquant/repo
```

### 5.5 首次部署

```bash
cd /opt/kwikquant/repo
bash docker/server-deploy-image.sh v1.2.3
# → git checkout v1.2.3 → docker compose pull(app+frontend) → docker pull worker → up -d → readiness 40×3s
# 成功:echo v1.2.3 > /opt/kwikquant/.last-good-tag
```

验证:

```bash
curl localhost:8080/actuator/health/readiness   # 期望 UP(app 127.0.0.1:8080)
curl localhost:8081/                              # 前端 SPA(frontend 容器 0.0.0.0:8081)
docker ps                                         # 见 postgres/app/frontend(edge profile 起则四容器)
```

### 5.6 TLS 终结 + 反代(三选一,按服务器形态)

CF Full strict 要求源站 :443 HTTPS。TLS 终结点(边缘层)三选一,都反代到 frontend 容器(8081 或容器名:80):

**A. 宝塔(裸 nginx 装在宿主)**

面板 nginx :443 + certbot 或 CF origin cert,反代到 frontend 容器 `127.0.0.1:8081`:

```nginx
# /www/server/panel/vhost/nginx/kwikquant.conf(宝塔)或 /etc/nginx/sites-available/
server {
    listen 443 ssl http2;
    server_name kwikquant.example.com;
    ssl_certificate /path/to/fullchain.pem;
    ssl_certificate_key /path/to/privkey.pem;
    location / {
        proxy_pass http://127.0.0.1:8081;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_read_timeout 86400;
    }
}
```

**B. 1Panel(OpenResty 容器化)**

1Panel 的 OpenResty 是容器,`127.0.0.1` 指容器自己不通。反代目标用 docker bridge gateway `172.17.0.1:8081`(指向宿主)或 `host.docker.internal:8081`:

1Panel 面板 → 网站 → 反向代理,目标 URL 填 `http://172.17.0.1:8081`,开 TLS + 挂 CF origin cert。或手改 OpenResty 配置(同 A,`proxy_pass http://172.17.0.1:8081`)。

**C. 无面板(裸 VPS,用 edge 容器)**

不起服务器 nginx,用 KwikQuant 自带的 edge 容器(profile `edge`)做 TLS 终结:

```bash
# 1. CF 面板 SSL/TLS → Origin Server → Generate Certificate(15 年免费 origin cert)
#    下载 fullchain.pem + privkey.pem 到服务器,如 /opt/kwikquant/cert/
# 2. 部署时 export TLS_CERT_DIR 指证书目录,deploy 脚本自动加 --profile edge
export TLS_CERT_DIR=/opt/kwikquant/cert
bash docker/server-deploy-image.sh v1.2.3
# edge 容器 :443 + origin cert → http://kwikquant-frontend:80(同 worker-net 容器名)
```

edge 容器配置见 `docker/edge/nginx.conf`。

> 三路径都终结 TLS 后反代到 frontend 容器。frontend 容器 serve SPA + 反代 /api /ws → app:8080,前端镜像化不用 scp。CF 模式 Full strict(CF→源站 HTTPS);源站证书:宝塔/1Panel 用 certbot 或 CF origin cert,无面板用 CF origin cert。
>
> 从旧版升级:旧裸 nginx 若直接 serve `/var/www` + 反代 app:8080,改成上面统一 `proxy_pass → frontend:8081`(frontend 容器接管 SPA + 反代),删 `/var/www` scp 流程。

### 5.7 服务器防火墙(必做)

挡源站公网直接访问,只允许 CF IP + 本机:

```bash
# ufw 示例(允许 SSH + CF IP :443,挡 :8081/:8080 公网)
sudo ufw default deny incoming
sudo ufw allow ssh
for ip in $(curl -s https://www.cloudflare.com/ips-v4); do
  sudo ufw allow from $ip to any port 443
done
sudo ufw allow 8081 comment 'kwikquant frontend(本机/容器访问,不公网)'
sudo ufw enable
```

> :8081 只给面板 nginx(本机/容器)访问,防火墙挡公网。CF 走 :443。:8080(app)绑 127.0.0.1 不对外。1Panel/宝塔自带防火墙也可,规则同上。

---

## 6. 日常发版

```bash
# 本地:打 tag push(触发 CI build 镜像)
git tag v0.1.1 && git push origin v0.1.1
# 等 Actions 跑完(GHCR 出现 v0.1.1 镜像)

# 服务器:部署
cd /opt/kwikquant/repo && git pull   # 拉最新 deploy 脚本/compose
bash docker/server-deploy-image.sh v0.1.1
# → pull 新镜像 → up → readiness 绿 → 更新 .last-good-tag
```

---

## 7. 回滚

`server-deploy-image.sh` **内置自动回滚**:新 tag readiness 超时(40×3s ≈ 2min)→ 自动 `up` 回 `.last-good-tag`。

手动回滚:

```bash
cat /opt/kwikquant/.last-good-tag              # 看上个好 tag
bash docker/server-deploy-image.sh v1.2.3      # 重新部署旧 tag
```

---

## 8. 从旧版升级:拆 push-to-deploy 链路

旧版用 GitHub webhook + 服务器 self-build(双路竞速)。新版用 tag + CI 镜像,旧链路拆除:

1. **GitHub repo**:Settings → Webhooks → 删指向服务器 :9000 的 webhook
2. **服务器**:`sudo systemctl disable --now kwikquant-webhook`(若有 kwikquant-webhook.service)
3. **仓库旧文件已删**:`docker/webhook-receiver.py`、`docker/kwikquant-webhook.service`、`docker/post-receive.sh`、`docker/server-deploy.sh`(被 `server-deploy-image.sh` 替代)
4. **服务器 bare repo**(若有):`/opt/kwikquant.git` 可删(`rm -rf /opt/kwikquant.git`)
5. **systemd service 文件**(若有):`sudo rm /etc/systemd/system/kwikquant-webhook.service && sudo systemctl daemon-reload`

---

## 9. 运维

```bash
# 日志
docker compose -f docker/docker-compose.prod.yml logs -f app
docker compose -f docker/docker-compose.prod.yml logs -f frontend
docker logs strategy-worker-{id}        # worker(按策略 id)

# 健康
curl localhost:8080/actuator/health/readiness
docker compose -f docker/docker-compose.prod.yml ps

# 进容器
docker exec -it kwikquant-app sh
docker exec -it kwikquant-postgres psql -U kwikquant kwikquant

# DB 备份(定时 cron 暂未接入)
docker exec kwikquant-postgres pg_dump -U kwikquant kwikquant > backup-$(date +%F).sql
```

---

## 10. 已知坑 + 待办

- **worker 镜像用 `:latest`**:`kwikquant.worker.image` 配 `ghcr.io/huiboxes/kwikquant-worker:latest`,与 app tag 可能错版。进阶用 tag + deploy 脚本覆盖 `kwikquant.worker.image`(待办)。
- **Flyway baseline**:`baseline-on-migrate: true`,首启对已有 DB baseline(V1)不破坏数据;空 DB 直接跑全部迁移。
- **secret 不可变**:`ENCRYPTION_KEY` / `JWT_SECRET` / `KWIKQUANT_MCP_PEPPER` 改了 = 已存 API key / refresh token / PAT 全失效。生产前一次定,妥善备份。
- **DB 备份/监控**:postgres volume 持久化已具备份雏形,定时 `pg_dump` + 告警待办。
