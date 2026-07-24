# IdolRadar 追星雷达

原生微信小程序 + 自建 Java 后端。追踪一位 idol 的 RSS 动态；发现新内容后发送微信订阅消息。

## MVP

- `wx.login` 静默登录；服务端 `code2Session` 获取 openid
- 单 idol 守护、切换、动态流游标分页
- RSS/Atom 每 30 分钟抓取、SSRF 防护、唯一链接去重
- 微信一次性订阅消息；额度原子扣减、失败补偿、推送幂等
- 三页：选择、雷达、我的

## 技术栈

- 小程序：原生 WXML/WXSS/JavaScript
- 后端：Java 21、Spring Boot 4、虚拟线程
- 数据：PostgreSQL 17、Redis 7
- 任务：同一 Java 制品的独立 Worker 模式 + PostgreSQL advisory lock
- 数据库版本：专用 `migrate` 模式执行 Flyway SQL；API/Worker 无 DDL 权限
- 部署：非 root Java 容器；生产由 HTTPS 负载均衡或反向代理暴露 API

生产后端、Worker、镜像均不依赖 Node.js。仓库的 Node.js 仅用于可选的小程序 JavaScript 契约和发布配置检查。

## 目录

```text
miniprogram/             微信小程序（开发者工具项目根，含 project.config.json）
backend/src/main/java/   Spring Boot API、认证、RSS Worker、推送、seed
backend/src/main/resources/db/migration/  PostgreSQL migration
backend/Dockerfile 等     后端镜像与部署配置（Dockerfile、compose*.yaml、.env.example）
database/                安全示例 seed；生产前必须替换
tests/                   小程序契约与发布校验
docs/                    PRD、功能设计、测试与部署手册
```

## 本机测试

要求：Docker。真实登录还需要真实小程序 AppID/AppSecret；只检查服务健康时可暂留占位值。

部署配置集中在 `backend/`，Docker 相关命令都在该目录下执行：

```bash
cp backend/.env.example backend/.env
# 编辑 backend/.env：数据库密码；联调登录时填写 WECHAT_APP_ID/WECHAT_APP_SECRET
cd backend
docker compose up -d postgres redis
docker compose run --rm migrate
docker compose --profile tools run --rm seed
docker compose up -d app
curl http://127.0.0.1:8080/readyz
```

微信开发者工具请**导入 `miniprogram/` 目录**作为项目（`project.config.json`/`project.private.config.json` 已随小程序代码放在其中，`miniprogramRoot` 为 `./`）。配置在 [miniprogram/config/env.js](miniprogram/config/env.js)（无密钥，已随仓库提交）：按需修改 `apiBaseUrl` 为可访问的 API 地址，上线前填写订阅消息模板 ID。

默认访问 `http://127.0.0.1:8080`。开发者工具本机 HTTP 调试需关闭合法域名校验；真机/体验版必须使用已加入微信后台合法域名的生产 HTTPS 地址。

Java 测试：

```bash
mvn -f backend/pom.xml test
mvn -f backend/pom.xml -Pintegration-test -Didolradar.it.enabled=true verify
```

第二条会用 Testcontainers 启动隔离的 PostgreSQL，验证 migration、约束、seed 幂等。可选客户端检查需要 Node.js 24：

```bash
npm ci
npm test
npm run validate
```

## 定时任务

宿主 cron 或 Kubernetes CronJob 每 30 分钟运行一次（在 `backend/` 目录下）：

```bash
docker compose --profile jobs run --rm fetch-feeds
```

任务使用 PostgreSQL advisory lock，重叠执行会安全跳过。Post 与通知 outbox 同事务；进程中断后下轮续跑。API 可水平扩容；Redis 统一管理跨实例限流和微信 access token。

## 上线校验

```bash
npm run validate:release
```

校验阻止本机 API 地址、缺失服务端密钥、示例 RSS、错误 AppID、缺少 Java/Flyway/Redis/容器配置进入发布。完整流程见[部署手册](docs/DEPLOYMENT.md)。

## 文档

- [功能设计](docs/MVP-功能设计.md)
- [产品需求 PRD](docs/MVP-产品需求PRD.md)
- [测试用例](docs/MVP-测试用例.md)
- [部署手册](docs/DEPLOYMENT.md)
