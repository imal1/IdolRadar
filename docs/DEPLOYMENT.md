# IdolRadar Docker 部署手册

目标：一台服务器、一份根目录 `compose.yaml`，一次启动 PostgreSQL、Redis、Flyway、
seed、Java API、定时 Worker、RSSHub、RSSHub 缓存。微信小程序及其自动化测试不进
Docker。

## 1. 服务器准备

- Debian 服务器，Docker Engine、Docker Compose
- 已解析到服务器的备案域名，以及已配置的宿主机 Nginx 与 TLS 证书
- 真实微信小程序 AppID、AppSecret、订阅消息模板 ID
- 已获授权的 idol 资料、头像和数据源
- 安全组仅放行 `22`、`80`、`443`

数据库密码、Redis 密码、AppSecret、Cookie 禁止提交 Git。PostgreSQL、Redis、
Java 调试端口都只绑定 `127.0.0.1`。

## 2. GitHub Actions 生产环境

生产 `.env` 不提交仓库。它作为一个完整的多行 Secret 保存到 GitHub `production`
Environment，只有 Release 的部署 Job 进入该环境后才能读取。首次配置可在项目根目录执行：

```powershell
gh api --method PUT repos/imal1/IdolRadar/environments/production
Get-Content -Raw .env | gh secret set PRODUCTION_ENV_FILE --env production
gh variable set DEPLOY_ENABLED --body false
```

`DEPLOY_ENABLED=false` 时，发布只构建并推送以下私有 GHCR 镜像，不连接服务器：

- `ghcr.io/imal1/idolradar-backend:<release-tag>`
- `ghcr.io/imal1/idolradar-rsshub:<release-tag>`

镜像同时更新 `latest` 标签。构建上下文只有 `backend/` 和 `rsshub/`，微信小程序不会进入
镜像或部署包。

## 3. 放置与配置

项目放到 `/opt/idolradar`。所有 Compose 命令都在该目录执行：

```bash
cd /opt/idolradar
chmod 600 .env
```

编辑 `.env`，至少替换：

```dotenv
POSTGRES_PASSWORD=数据库强密码
REDIS_PASSWORD=Redis强密码
WECHAT_APP_ID=真实小程序AppID
WECHAT_APP_SECRET=真实AppSecret
SUBSCRIBE_TEMPLATE_ID=已审核模板ID
SUBSCRIBE_IDOL_FIELD=thing1
SUBSCRIBE_TITLE_FIELD=thing2
SUBSCRIBE_TIME_FIELD=time3
NOTIFICATIONS_ENABLED=true
WEIBO_COOKIES=已授权Cookie
```

### 3.1 微信订阅消息模板契约

在微信公众平台的订阅消息模板详情中确认模板 ID 与三个字段的完整名称，并按实际序号填写：

| 业务内容 | 微信字段类型 | `.env` 配置 | 服务端限制 |
|---|---|---|---|
| idol 名 | `thing<number>.DATA` | `SUBSCRIBE_IDOL_FIELD` | 20 个 Unicode 字符，超出截断 |
| 动态标题 | `thing<number>.DATA` | `SUBSCRIBE_TITLE_FIELD` | 20 个 Unicode 字符，超出截断 |
| 发布时间 | `time<number>.DATA` | `SUBSCRIBE_TIME_FIELD` | Asia/Shanghai 的 `HH:mm` |

`number` 是平台为当前模板生成的序号，不要假定一定为 `1/2/3`。小程序
`miniprogram/config/env.js` 的 `subscribeTemplateId` 必须填写同一个模板 ID；模板 ID 会随小程序
代码下发，不属于 AppSecret，但禁止把 `WECHAT_APP_SECRET` 放进小程序配置。

配置后先校验，再重新创建 API/Worker 容器并重新编译小程序：

```bash
pnpm run validate:release
docker compose up -d --force-recreate app worker
```

真机点击“去开启”并同意一次性订阅后，手工运行一轮 Worker。验收消息中的 idol 名、动态标题、
时间，以及点击后 `pages/radar/index?postId=<动态ID>` 能准确定位对应动态。开发者工具不能代替
真实微信消息到达验证。

宿主机 Nginx 保持监听 `80/443`，将 `app.imali.top` 反向代理到
`http://127.0.0.1:8080`。证书与 Nginx 配置不由本项目 Compose 修改。

## 4. GitHub Actions 发布部署

服务器部署需要在 GitHub `production` Environment 中配置：

| 类型 | 名称 | 内容 |
|---|---|---|
| Secret | `PRODUCTION_ENV_FILE` | 完整的生产 `.env` |
| Secret | `DEPLOY_HOST` | 服务器域名或 IP |
| Secret | `DEPLOY_USER` | SSH 用户 |
| Secret | `DEPLOY_SSH_KEY` | SSH 私钥全文 |
| Secret | `DEPLOY_KNOWN_HOSTS` | `ssh-keyscan` 得到的服务器主机公钥记录 |
| Variable | `DEPLOY_PORT` | SSH 端口，默认 `22` |
| Variable | `DEPLOY_PATH` | 部署目录，默认 `/opt/idolradar` |

服务器需要安装 Docker Engine 与 Docker Compose，SSH 用户需要能执行 Docker。配置完成后
启用部署：

```powershell
gh variable set DEPLOY_ENABLED --body true
```

推送以 `v` 开头的版本标签会自动部署；普通分支提交和合并到 `main` 不会触发部署：

```powershell
git tag v0.1.0
git push origin v0.1.0
```

也可以进入仓库的 `Actions` → `release` → `Run workflow`，选择要部署的分支后手动运行。
`image_tag` 可选；留空时使用 `manual-<commit SHA>`，填写时必须是合法的 Docker 标签。

无论由版本标签自动触发还是手动运行，工作流都会先在 GitHub Actions 构建两个镜像并推送
到私有 GHCR，再把
`compose.yaml`、`database/` 和临时生成的 `.env` 传到服务器。服务器只拉取
已构建镜像并执行 `docker compose up -d --no-build`，不需要 Maven、Node.js 或小程序工具链。

## 5. 服务器直接部署

校验、构建、启动：

```bash
cd /opt/idolradar
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 migrate seed app worker rsshub
```

启动顺序由 Compose 保证：

1. PostgreSQL、Redis、RSSHub 缓存健康。
2. Flyway `migrate` 成功。
3. 幂等 `seed` 成功。
4. API、RSSHub 健康。
5. Worker 启动。

`migrate`、`seed` 正常状态是 `Exited (0)`；其余服务应为 `Up`/`healthy`。

验收：

```bash
curl http://127.0.0.1:8080/healthz
curl http://127.0.0.1:8080/readyz
curl https://你的域名/readyz
```

微信公众平台必须把 `https://你的域名` 加入 `request` 合法域名；小程序
`miniprogram/config/env.js` 的 `apiBaseUrl` 使用相同 HTTPS origin。

## 6. 数据与定时 Worker

Flyway SQL 位于 `backend/src/main/resources/db/migration/`。已执行 migration 永不修改；
结构变化新增版本。API、Worker、seed 均禁止自动 DDL。

`database/*.seed.jsonl` 在部署时只读挂载。Seed 使用事务和 upsert，不会清空抓取状态。
默认 Feed origin 是 Docker 私网 `http://rsshub:1200`，不暴露 RSSHub 的 `1200` 端口。
微博 Cookie 只注入 RSSHub，不进入 Java 容器。

Worker 容器默认每 30 分钟执行。每轮从上一轮结束后计算延迟；PostgreSQL advisory lock
阻止多实例重入。Post 与 `notification_outbox` 同事务写入，失败任务下轮恢复。

修改周期：

```dotenv
WORKER_INTERVAL=PT30M
WORKER_INITIAL_DELAY=PT5S
```

应用配置：

```bash
docker compose up -d --force-recreate worker
```

手工立即运行一次：

```bash
docker compose run --rm \
  -e IDOLRADAR_WORKER_SCHEDULE_ENABLED=false \
  worker
```

## 7. 升级与回滚

更新代码后：

```bash
cd /opt/idolradar
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 migrate seed app worker rsshub
```

Compose 会复用 PostgreSQL、Redis 命名卷。禁止执行 `docker compose down -v`，否则会删除
业务数据。

回滚代码时切回上一版本，再运行 `docker compose up -d --build`。数据库 migration 采用
前向修复，不删除已执行版本。升级前必须备份：

```bash
docker compose exec -T postgres \
  pg_dump -U idolradar -d idolradar -Fc > /opt/idolradar-backup.dump
```

## 8. 运维命令

```bash
docker compose ps
docker compose logs -f app worker rsshub
docker compose restart app worker rsshub
docker compose exec postgres psql -U idolradar -d idolradar
docker compose exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
```

Navicat 连接 PostgreSQL：先建 SSH 隧道到服务器，再连接 `127.0.0.1:5432`；用户名、
数据库名、密码取服务器 `.env`。不要在安全组放行 `5432/6379/8080/1200`。

## 9. 上线验收

```bash
mvn -f backend/pom.xml test
mvn -f backend/pom.xml -Pintegration-test -Didolradar.it.enabled=true verify
pnpm test
pnpm run validate:release
docker compose config --quiet
```

- `/healthz`：JVM 存活。
- `/readyz`：PostgreSQL、Redis 可用。
- 真机闭环：登录、选择 idol、授权订阅、Worker 获取新动态、入库、收到消息、点击返回。
- 重跑 Worker：不得重复 post、不得重复推送。
- 监控：API 5xx/401/429、JVM、Hikari、数据库/Redis、Worker 最近成功时间、RSS 连续失败、
  通知 `retryable/uncertain` 积压。

生产至少配置数据库备份、磁盘告警、容器重启告警。容器管理权限等同服务器 root 权限，
必须限制并审计。
