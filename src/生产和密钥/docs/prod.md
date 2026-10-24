# 生产相关信息

> 服务端部署的**权威文档**在仓库里：[`aero-server/deploy/README.md`](../aero-server/deploy/README.md)（拓扑、CI/CD、首次部署实录、验证、回滚全在里面）。
> 这里只放「要连机器时手边要看的东西」，最后核对时间 **2026-10-18 18:37Z**（下面的机器事实都是那次实测的）。
> 路径若无特别说明，都相对工作区根目录 `/Users/duzhengheng/Temp/workspace/aero`。

## 机器

| 项 | 值 | 实测 |
|---|---|---|
| 服务器地址 | `18.143.226.219` | — |
| 实例 | AWS EC2 **t3.medium**，ap-southeast-1，私网 `10.10.11.167` | 来自部署文档 |
| 系统 | Ubuntu **26.04 LTS**（`resolute`） | ✅ |
| 内核/容器 | Docker **29.9.1**，Compose **5.6.0** | ✅ |
| SSH 端口 | **222** | ✅ |
| SSH 用户 | `ubuntu`（已在 `docker` 组，CI 才能不带 sudo 部署） | — |
| 密钥文件 | `certs/aero-master.pem` | ✅ |

```bash
# ⚠️ 权限必须是 600。实测曾经是 644，ssh 会直接拒绝加载：
#    "Permissions 0644 for 'certs/aero-master.pem' are too open ... This private key will be ignored"
#    表现为 Permission denied (publickey)，很容易误判成密钥错/被踢。
chmod 600 certs/aero-master.pem

ssh -i certs/aero-master.pem -p 222 ubuntu@18.143.226.219
```

> 该 `.pem` 只放在工作区、**不进仓库**；Gitea 里的 `PROD_SERVER_KEY` secret 存的是它的完整内容。

## 服务

| 项 | 值 | 实测 |
|---|---|---|
| API 地址 | `http://18.143.226.219:8787`（**无 TLS**） | ✅ 0.30s 应答 |
| 健康检查 | `GET /api/health` | ✅ `{"ok":true,"mode":"live","agentConfigured":true,"browserConfigured":false}` |
| 部署目录（机器上） | `/home/ubuntu/docker-compose/aero` | ✅ |
| 代码仓库 | `http://47.109.137.67:9091/aero/aero-server`（对外 `code.paipai.life`） | ✅ |
| 镜像 | `501602545152.dkr.ecr.ap-southeast-1.amazonaws.com/areo/server:latest`（同时有 `:<sha>`） | ✅ |

三个容器（`aero-api` / `aero-task-worker` 用的是同一个镜像、只差启动入口）：

| 容器 | 镜像 | 端口 | 实测状态 |
|---|---|---|---|
| `aero-api` | `areo/server:latest` | `0.0.0.0:8787->8787` | Up (healthy) |
| `aero-postgres` | `postgres:17-alpine` | 仅容器网络 `5432` | Up (healthy) |
| `aero-task-worker` | `areo/server:latest` | 不发布 | Up |

```bash
# 从本机验活
curl -sS -m 10 http://18.143.226.219:8787/api/health

# 上机器看状态
ssh -i certs/aero-master.pem -p 222 ubuntu@18.143.226.219
cd /home/ubuntu/docker-compose/aero
docker compose -f docker-compose-prod.yaml ps
docker compose -f docker-compose-prod.yaml logs --tail 50 api task-worker
# ↑ 这里要写 service 名（api / task-worker），不是 container 名（aero-api），否则报 "no such service: aero-api"
```

> **连不上先分清两种情况**：安全组没放行 → 包被丢弃，`curl` 挂满超时（实测 `t=8.00s`）；放行但没进程监听 → 回 RST，`curl` 立刻 `Connection refused`。
> 8787 需要在 AWS 控制台给该实例的安全组加入站规则：类型「自定义 TCP」、端口 `8787`、来源 `0.0.0.0/0`。
> **Docker 的端口发布绕过 UFW**，所以安全组和 UFW 两处都要看。

## 发布

**唯一的部署开关是 `push prod`**：`.gitea/workflows/build-prod.yaml` → 构建并推 ECR（`:latest` + `:<sha>`）→ SSH 到 222 → 覆盖 compose → `compose up -d --force-recreate` → 轮询 `/api/health` 最多 150s（75×2s），失败自动打最近 80 行日志。

```bash
# 日常开发在 main（只跑上游 ci.yml，不部署）
git checkout main && git commit -am "feat: ..." && git push origin main

# 要发布：把 main 快进到 prod，再推 prod
git checkout prod && git merge --ff-only main && git push origin prod
```

- `main` 已于 2026-10-18 快进追上 `prod`，现在两者同指 `ae3fd25`。
- **`prod` 上任何提交都会重新构建并部署一次**，包括纯文档提交（run #3 就是一条 `docs(deploy): ...`）。
- 推 `main` 不部署。（细节与 Gitea 侧实测见部署文档的「分支策略」一节。）

## 机器上的目录与固定配置

```
/home/ubuntu/docker-compose/aero/
├── docker-compose-prod.yaml   # 600，每次部署被 CI 覆盖 —— 不要手改
├── .env                       # 600，手工维护，CI 不覆盖
├── data/                      # API 与 worker 共享的 DATA_DIR（PDF 等）
└── pgdata/                    # Postgres 数据（root:root，容器内 uid 70）
```

`HOST` / `PORT` / `DATA_DIR` / `DATABASE_URL` / `TASK_WORKER_ENABLED` 由 compose 的 `environment:` 钉死（优先于 `env_file:`），**不要在 `.env` 里覆盖它们**。

`.env` 里各项的归属：

- **必须由你本人提供**：`CPK_INTELLIGENCE_API_KEY`（`npx copilotkit@latest login` 后生成，任何模式都必填）、`MODEL` + 对应厂商的 API key。
- **丢了会出事**：`TOKEN_ENCRYPTION_KEY`（丢了库里已存的 OAuth token 全部解不开，需单独备份）、`POSTGRES_PASSWORD`（只在**首次**初始化时被 postgres 采纳，之后改 `.env` 不会改已有库的密码）。
- **可随机生成**：`OPENMUSE_ACCESS_KEY`（≥24 字符）。

## 当前已知风险

- **无 TLS**：`OPENMUSE_ACCESS_KEY`、LemurX 设备令牌都是明文过公网。能用来源 IP 限制就先限制，拿到域名后尽快上反向代理 + 证书。
- **单 owner**：`aero-server/SECURITY.md:9` 明确写着 “one owner per deployment … not multi-tenant account authentication”。这个 access key 等于**所有人的凭证**，在多用户改造完成前不要分发。
- **浏览器不可用**：生产 `/api/health` 报 `browserConfigured:false` —— 没有部署 browser worker，`apps/worker` 那条链路在 prod 上是关的。
- **备份**：`pgdata/` 是宿主机 bind mount，直接 `tar` 即可（注意 uid 70）；逻辑备份 `docker compose exec postgres pg_dump -U aero aero`。
- **日志**：compose 已钉死 `json-file` + `max-size 50m` + `max-file 3`，**不要删这三行**（lemur 那台生产机曾因 json.log 无上限写满磁盘）。
- **回滚**：正规做法是在 `prod` 上 revert 再 push；应急可拿 ECR 里的 `:<sha>` 标签重打 `:latest` 后 `compose up -d --force-recreate`（步骤见部署文档）。
