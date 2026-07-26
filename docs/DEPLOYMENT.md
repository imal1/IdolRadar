# IdolRadar Docker 部署手册

目标：一台服务器、一份根目录 `compose.yaml`，一次启动 PostgreSQL、Redis、Flyway、
seed、Java API、定时 Worker、RSSHub、RSSHub 缓存、Nginx。微信小程序及其自动化测试不进
Docker。

## 1. 服务器准备

- Debian 服务器，Docker Engine、Docker Compose
- 已解析到服务器的备案域名
- Nginx 格式 TLS 证书与私钥
- 真实微信小程序 AppID、AppSecret、订阅消息模板 ID
- 已获授权的 idol 资料、头像和数据源
- 安全组仅放行 `22`、`80`、`443`

数据库密码、Redis 密码、AppSecret、Cookie、证书私钥禁止提交 Git。PostgreSQL、Redis、
Java 调试端口都只绑定 `127.0.0.1`。

## 2. 放置与配置

项目放到 `/opt/idolradar`。所有 Compose 命令都在该目录执行：

```bash
cd /opt/idolradar
cp .env.example .env
chmod 600 .env
```

编辑 `.env`，至少替换：

```dotenv
POSTGRES_PASSWORD=数据库强密码
REDIS_PASSWORD=Redis强密码
SERVER_NAME=你的域名
TLS_CERT_FILE=/opt/idolradar/certs/证书文件.crt
TLS_KEY_FILE=/opt/idolradar/certs/私钥文件.key
WECHAT_APP_ID=真实小程序AppID
WECHAT_APP_SECRET=真实AppSecret
SUBSCRIBE_TEMPLATE_ID=已审核模板ID
NOTIFICATIONS_ENABLED=true
WEIBO_COOKIES=已授权Cookie
```

`TLS_CERT_FILE`、`TLS_KEY_FILE` 必须是服务器绝对路径。Nginx 证书压缩包解压后，选
`.crt`/`.pem` 证书链和 `.key` 私钥；私钥建议 `chmod 600`。

## 3. 一次部署

若服务器已有宿主机 Nginx 占用 `80/443`，先停止并禁用，避免端口冲突：

```bash
systemctl disable --now nginx
```

校验、构建、启动：

```bash
cd /opt/idolradar
docker compose config --quiet
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 migrate seed app worker rsshub nginx
```

启动顺序由 Compose 保证：

1. PostgreSQL、Redis、RSSHub 缓存健康。
2. Flyway `migrate` 成功。
3. 幂等 `seed` 成功。
4. API、RSSHub 健康。
5. Worker 与 Nginx 启动。

`migrate`、`seed` 正常状态是 `Exited (0)`；其余服务应为 `Up`/`healthy`。

验收：

```bash
curl http://127.0.0.1:8080/healthz
curl http://127.0.0.1:8080/readyz
curl https://你的域名/readyz
```

微信公众平台必须把 `https://你的域名` 加入 `request` 合法域名；小程序
`miniprogram/config/env.js` 的 `apiBaseUrl` 使用相同 HTTPS origin。

## 4. 数据与定时 Worker

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

## 5. 升级与回滚

更新代码后：

```bash
cd /opt/idolradar
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 migrate seed app worker nginx
```

Compose 会复用 PostgreSQL、Redis 命名卷。禁止执行 `docker compose down -v`，否则会删除
业务数据。

回滚代码时切回上一版本，再运行 `docker compose up -d --build`。数据库 migration 采用
前向修复，不删除已执行版本。升级前必须备份：

```bash
docker compose exec -T postgres \
  pg_dump -U idolradar -d idolradar -Fc > /opt/idolradar-backup.dump
```

## 6. 运维命令

```bash
docker compose ps
docker compose logs -f app worker rsshub nginx
docker compose restart app worker rsshub nginx
docker compose exec postgres psql -U idolradar -d idolradar
docker compose exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
```

Navicat 连接 PostgreSQL：先建 SSH 隧道到服务器，再连接 `127.0.0.1:5432`；用户名、
数据库名、密码取服务器 `.env`。不要在安全组放行 `5432/6379/8080/1200`。

## 7. 上线验收

```bash
mvn -f backend/pom.xml test
mvn -f backend/pom.xml -Pintegration-test -Didolradar.it.enabled=true verify
pnpm test
pnpm run validate:release
docker compose --env-file .env.example config --quiet
```

- `/healthz`：JVM 存活。
- `/readyz`：PostgreSQL、Redis 可用。
- 真机闭环：登录、选择 idol、授权订阅、Worker 获取新动态、入库、收到消息、点击返回。
- 重跑 Worker：不得重复 post、不得重复推送。
- 监控：API 5xx/401/429、JVM、Hikari、数据库/Redis、Worker 最近成功时间、RSS 连续失败、
  通知 `retryable/uncertain` 积压。

生产至少配置数据库备份、磁盘告警、容器重启告警。容器管理权限等同服务器 root 权限，
必须限制并审计。
