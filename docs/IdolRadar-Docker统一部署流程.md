# IdolRadar Docker 统一部署流程

适用范围：Debian 服务器，项目目录 `/opt/idolradar`。

部署内容：

- PostgreSQL 17
- 业务 Redis
- Flyway 数据库迁移
- Java seed
- Spring Boot API
- 常驻定时 Worker
- RSSHub 与独立缓存 Redis
- Nginx HTTPS

微信小程序 `miniprogram/` 和小程序自动化测试 `tests/miniprogram-e2e/` 不进入 Docker。

## 一、服务器准备

安装 Docker 与 Compose：

```bash
apt update
apt install -y docker.io docker-compose
systemctl enable --now docker
docker --version
docker compose version
```

腾讯云安全组只放行：

```text
22    SSH
80    HTTP 跳转 HTTPS
443   HTTPS
```

不要对公网放行 `5432`、`6379`、`8080`、`1200`。

## 二、项目目录

推荐结构：

```text
/opt/idolradar/
├── .env
├── compose.yaml
├── backend/
├── database/
├── deploy/nginx/
├── miniprogram/
└── rsshub/
```

所有 `docker compose` 命令必须在 `/opt/idolradar` 执行。

## 三、配置 `.env`

首次部署：

```bash
cd /opt/idolradar
cp .env.example .env
chmod 600 .env
```

编辑 `.env`，替换所有占位值：

```dotenv
COMPOSE_PROJECT_NAME=idolradar

POSTGRES_DB=idolradar
POSTGRES_USER=idolradar
POSTGRES_PASSWORD=数据库强密码
POSTGRES_PORT=5432

REDIS_PASSWORD=Redis强密码
REDIS_PORT=6379
APP_PORT=8080

SERVER_NAME=你的域名
TLS_CERT_FILE=/opt/idolradar/certs/证书文件.crt
TLS_KEY_FILE=/opt/idolradar/certs/私钥文件.key

WECHAT_APP_ID=真实小程序AppID
WECHAT_APP_SECRET=真实AppSecret
SUBSCRIBE_TEMPLATE_ID=订阅消息模板ID
MINIPROGRAM_STATE=formal

RSSHUB_BASE_URL=http://rsshub:1200
RSS_TRUSTED_ORIGINS=http://rsshub:1200
WEIBO_COOKIES=已授权的微博Cookie

WORKER_INTERVAL=PT30M
WORKER_INITIAL_DELAY=PT5S

# 首次只测试 RSS 入库时保持 false；模板与真机授权验证完成后改为 true。
NOTIFICATIONS_ENABLED=false
```

安全要求：

- `.env`、Cookie、AppSecret、数据库密码、Redis 密码不得提交 Git。
- TLS 私钥不得放入 Git。
- `TLS_CERT_FILE`、`TLS_KEY_FILE` 使用服务器绝对路径。

## 四、证书

创建目录并放入 Nginx 证书：

```bash
mkdir -p /opt/idolradar/certs
chmod 700 /opt/idolradar/certs
chmod 600 /opt/idolradar/certs/*
```

确认 `.env` 中的证书路径与实际文件一致。

如果宿主机 Nginx 已占用 `80/443`，停用旧服务：

```bash
systemctl disable --now nginx
```

## 五、首次部署

配置检查：

```bash
cd /opt/idolradar
docker compose config --quiet
```

一次构建并启动全部服务：

```bash
docker compose up -d --build
```

查看状态：

```bash
docker compose ps
docker compose logs --tail=100 migrate seed app worker rsshub nginx
```

正常状态：

- `migrate`、`seed`：`Exited (0)`
- `postgres`、`redis`、`rsshub-cache`、`rsshub`、`app`、`worker`、`nginx`：`Up`
- 带健康检查的服务最终显示 `healthy`

## 六、上线验证

服务器本机：

```bash
curl http://127.0.0.1:8080/healthz
curl http://127.0.0.1:8080/readyz
```

公网 HTTPS：

```bash
curl https://你的域名/readyz
```

微信公众平台：

1. 将 `https://你的域名` 加入 `request` 合法域名。
2. 将小程序 `miniprogram/config/env.js` 的 `apiBaseUrl` 改为相同 HTTPS origin。
3. 确认小程序 AppID 与服务器 `WECHAT_APP_ID` 一致。

## 七、Worker 与真实数据

Worker 容器常驻，默认每 30 分钟运行。PostgreSQL advisory lock 防止重复执行。

手工立即执行一次：

```bash
docker compose run --rm \
  -e IDOLRADAR_WORKER_SCHEDULE_ENABLED=false \
  worker
```

查看入库数据：

```bash
docker compose exec postgres \
  psql -U idolradar -d idolradar \
  -c "SELECT channel,title,link,published_at FROM posts ORDER BY published_at DESC LIMIT 10;"
```

确认 RSS 入库正常、订阅模板已审核、真机已授权后：

```dotenv
NOTIFICATIONS_ENABLED=true
```

应用配置：

```bash
docker compose up -d --force-recreate worker
```

## 八、日常升级

更新代码后：

```bash
cd /opt/idolradar
docker compose up -d --build
docker compose ps
docker compose logs --tail=100 migrate seed app worker nginx
```

BuildKit 会复用 Maven 缓存。Flyway migration 与 seed 会在需要时执行。

禁止执行：

```bash
docker compose down -v
```

`-v` 会删除 PostgreSQL、Redis 数据卷。

## 九、备份与回滚

升级前备份 PostgreSQL：

```bash
docker compose exec -T postgres \
  pg_dump -U idolradar -d idolradar -Fc \
  > /opt/idolradar-backup.dump
```

代码回滚：

1. 切回上一稳定 Git 版本。
2. 执行 `docker compose up -d --build`。
3. 数据库 migration 不降级，使用新的前向修复 migration。

## 十、常用运维命令

```bash
docker compose ps
docker compose logs -f app worker rsshub nginx
docker compose restart app worker rsshub nginx
docker compose exec postgres psql -U idolradar -d idolradar
docker compose exec redis sh -c 'REDISCLI_AUTH="$REDIS_PASSWORD" redis-cli ping'
```

Navicat 连接 PostgreSQL：使用 SSH 隧道连接服务器，再访问服务器回环地址
`127.0.0.1:5432`。数据库名、用户名、密码取服务器 `.env`。
