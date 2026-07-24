# IdolRadar Java 后端部署手册

目标：微信小程序 + Spring Boot HTTPS API + PostgreSQL + Redis + 独立 Worker。CloudBase 与 Node.js 不参与生产后端运行。

## 1. 上线前准备

- 已认证小程序及真实 AppID/AppSecret
- 已审核的一次性订阅消息模板；字段为 `thing1`、`thing2`、`time3`
- 已备案 API 域名，例如 `https://api.example.com`
- TLS 1.2+ 公网证书
- Java 21 容器运行环境；PostgreSQL 17；Redis 7
- 已获授权的 idol 资料、头像、公开 HTTPS RSS 源
- 测试、生产环境和凭证完全隔离

AppSecret、数据库密码、微信 access token、openid、生产数据不得提交 Git。生产使用 Secret Manager；PostgreSQL、Redis 只开放私网。

## 2. 配置

Compose 读取 `.env`，至少填写：

```dotenv
POSTGRES_DB=idolradar
POSTGRES_USER=idolradar
POSTGRES_PASSWORD=strong-database-password
DATABASE_POOL_SIZE=20
WECHAT_APP_ID=wx0000000000000000
WECHAT_APP_SECRET=server-only-secret
SUBSCRIBE_TEMPLATE_ID=approved-template-id
MINIPROGRAM_STATE=formal
LOG_LEVEL=INFO
```

容器内使用 Spring 标准变量：

```dotenv
SPRING_DATASOURCE_URL=jdbc:postgresql://PRIVATE_DB_HOST:5432/idolradar
SPRING_DATASOURCE_USERNAME=idolradar
SPRING_DATASOURCE_PASSWORD=strong-database-password
SPRING_DATA_REDIS_HOST=PRIVATE_REDIS_HOST
SPRING_DATA_REDIS_PORT=6379
IDOLRADAR_WECHAT_APP_ID=wx0000000000000000
IDOLRADAR_WECHAT_APP_SECRET=server-only-secret
IDOLRADAR_SUBSCRIBE_TEMPLATE_ID=approved-template-id
```

- `WECHAT_APP_ID` 必须与 `project.config.json` 一致。
- `WECHAT_APP_SECRET` 只允许后端读取。
- `miniprogram/config/env.js` 的 `apiBaseUrl` 改为生产 HTTPS origin；模板 ID 与服务端一致。
- 微信公众平台把 API 域名加入 `request` 合法域名。头像外域加入对应下载合法域名。
- 小程序不得直连 `api.weixin.qq.com`、PostgreSQL 或 Redis。

## 3. 数据库版本

Migration 位于 `backend/src/main/resources/db/migration/`。Flyway 随 Java 应用打包，只由一次性的 `APP_MODE=migrate` 执行 SQL 并记录版本，不是额外服务。API、Worker、seed 明确设置 `SPRING_FLYWAY_ENABLED=false`，运行账号无需 DDL 权限。

```bash
docker compose run --rm migrate
```

发布原则：

- 已在生产执行的 migration 永不修改；结构变化新增下一版本 SQL。
- 先运行向后兼容 migration，再滚动发布 API。
- Flyway 失败时停止发布，不让应用带着半套表结构接流量。
- 数据库启用自动备份/PITR；上线前完成恢复演练。

旧 Node 迁移器创建的非空数据库没有 Flyway history，不能直接打开 `baseline-on-migrate` 猜版本。先备份并核对实际 schema，再由 DBA 按已执行版本建立 baseline；不确定时迁入全新数据库。Compose 使用新的 `postgres-java-data` 卷，旧本机卷不会被删除或误接管。

核心约束包括 `users.openid UNIQUE`、`posts.link UNIQUE`、动态游标索引、会话 token 哈希、订阅额度检查及 `(post_id,user_id)` 推送幂等键。

## 4. 正式 seed

仓库 `database/*.seed.jsonl` 使用 `example.invalid`，只能验证结构。生产必须替换为获授权资料和公网 HTTPS RSS。

```bash
node scripts/validate-project.js --seed-dir /absolute/path/production-seeds
docker compose --profile tools run --rm \
  -v /absolute/path/production-seeds:/app/database:ro seed
```

Node 命令仅做离线配置校验；导入本身由 Java `SeedService` 执行。Seed 使用事务和 upsert，不重置抓取状态。

## 5. 单机/预发布部署

```bash
docker compose up -d postgres redis
docker compose run --rm migrate
docker compose --profile tools run --rm seed
docker compose up -d app
curl http://127.0.0.1:8080/healthz
curl http://127.0.0.1:8080/readyz
```

- `/healthz` 仅表示 JVM 存活。
- `/readyz` 同时检查 PostgreSQL 与 Redis；失败时负载均衡不得导流。
- Compose 默认只在 `127.0.0.1` 暴露 8080、5432、6379。
- HTTPS 反向代理只转发 `/v1/*`、`/healthz`、`/readyz`。
- `SERVER_FORWARD_HEADERS_STRATEGY=framework` 仅适用于 origin 无法被公网绕过、只接受可信代理连接的拓扑。

生产至少两个无状态 API 副本，由负载均衡分发。Hikari 连接池大小乘以副本数不得超过 PostgreSQL 可用连接预算。Redis 采用托管高可用实例；限流不可用时 API fail closed。

正式部署使用独立的 [compose.prod.yaml](../compose.prod.yaml)，不启动仓库内 PostgreSQL/Redis，不提供弱默认值，并强制注入镜像、数据库、Redis、微信凭证。执行顺序：

```bash
docker compose -f compose.prod.yaml run --rm migrate
docker compose -f compose.prod.yaml up -d app
```

`migrate` 使用独立 DDL 账号；API/Worker 使用仅 DML 账号。生产 Redis 强制 TLS 和密码。变量应由平台 Secret Manager 注入进程环境；不要把生产值写进 `.env`、Compose 文件或 Git。拥有容器管理权限者本就等同主机管理员，仍须最小化该权限并审计 `docker inspect`。

## 6. 登录链路

1. 小程序 `wx.login()` 取得一次性 code。
2. `POST /v1/auth/wechat/login`。
3. Java API 调用微信 `code2Session`；只信微信返回的 openid。
4. 服务端建档，签发 256-bit 随机会话 token；数据库只存 SHA-256。
5. 客户端使用 `Authorization: Bearer <token>`。
6. 401 时客户端单飞重新登录，原请求最多重试一次。

禁止返回或持久化 `session_key`；禁止接受客户端传入 openid。

## 7. RSS 与推送 Worker

API 副本不运行定时器。调度平台每 30 分钟启动同一 Java 镜像的 `APP_MODE=worker`：

```bash
docker compose --profile jobs run --rm fetch-feeds
```

标准 cron：

```cron
*/30 * * * * cd /srv/idolradar && docker compose --profile jobs run --rm fetch-feeds
```

Worker 使用 PostgreSQL advisory lock 防重叠；RSS 请求执行 HTTPS、DNS/IP、重定向、响应大小、XML 外部实体限制。Redis 缓存微信 access token并用分布式锁防止刷新风暴。

Post 与 `notification_outbox` 在同一事务写入；outbox 按 idol 合并最新动态，包含 lease、重试时间和状态。Worker 崩溃或微信全局错误中止后，下轮恢复；用户级 `notification_deliveries` 保证续跑不重复发送。Worker 完成时输出单行 JSON 汇总，API 响应返回 `X-Request-Id` 并写入日志 MDC。

推送状态为 `reserved/sending/retryable/sent/failed/uncertain`：确定未发送可退避重试；HTTP 结果不确定不自动重试，避免重复消息；`notification_deliveries` 保证同用户、同动态幂等。

## 8. 验收

自动化：

```bash
mvn -f backend/pom.xml test
mvn -f backend/pom.xml -Pintegration-test -Didolradar.it.enabled=true verify
npm ci
npm test
npm run validate:release
docker compose config
```

真机闭环：新用户登录 → 选择 idol → 允许一次订阅 → 可控 RSS 增加唯一链接 → 手动运行 Worker → 收到消息 → 点击回雷达页 → 重跑 Worker 不重复入库、不重复推送。

开发者工具不能替代真实订阅消息送达测试。`docs/MVP-测试用例.md` 的 P0 与 E2E-01 未通过时禁止上线。

## 9. 监控、回滚、安全

监控 API 5xx/401/429/P95、JVM、Hikari、PostgreSQL、Redis、Worker 最近成功时间、RSS 连续失败、微信错误码和 `retryable/uncertain` 积压。

回滚顺序：先停 Worker 调度；API 回滚上一镜像；小程序回滚上一审核版本；数据库采用前向修复 migration。数据修复前先备份。

- [ ] Secret 全部进入 Secret Manager
- [ ] API HTTPS 域名已加入微信合法域名
- [ ] PostgreSQL/Redis 无公网端口，最小权限且有备份
- [ ] 容器非 root；依赖扫描无已知高危漏洞
- [ ] 生产 seed 无示例域名，资料/RSS/头像已授权
- [ ] 隐私指引说明 openid、订阅消息、动态链接用途
- [ ] 真机 P0 闭环、回滚、备份、告警均完成
