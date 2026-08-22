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
- 任务：同一 Java 制品的常驻 Worker 模式 + Spring 定时调度 + PostgreSQL advisory lock
- 数据库版本：专用 `migrate` 模式执行 Flyway SQL；API/Worker 无 DDL 权限
- 部署：根目录单一 Docker Compose；生产 HTTPS 由服务器宿主机 Nginx 反向代理

生产后端、Worker、镜像均不依赖 Node.js。仓库的 Node.js 仅用于可选的小程序 JavaScript 契约和发布配置检查。

## 目录

```text
miniprogram/             微信小程序（开发者工具项目根，含 project.config.json）
backend/src/main/java/   Spring Boot API、认证、RSS Worker、推送、seed
backend/src/main/resources/db/migration/  PostgreSQL migration
backend/Dockerfile       Java 后端镜像
compose.yaml             PostgreSQL、Redis、API、Worker、RSSHub 统一编排
rsshub/                  指向 imal1/RSSHub 的源码子模块，用于 route 开发和镜像构建
packages/test-utils/      RSSHub/小程序测试可复用的纯 Node.js 工具
database/                王一博与 RSSHub 微博 route seed
tests/miniprogram-e2e/    Vitest + miniprogram-automator 小程序 E2E
tests/                   小程序契约、E2E 与发布校验
docs/                    PRD、功能设计、测试与部署手册
```

## 本机测试

要求：Docker、Node.js 24。所有 Compose 命令都在项目根目录执行：

```bash
# 编辑 .env：密码与微信配置
corepack enable pnpm
pnpm install --frozen-lockfile
pnpm run admin:build
docker compose up -d --build
docker compose ps
curl http://127.0.0.1:8080/readyz
```

`pnpm run admin:build` 不能省。管理端产物写在 `backend/src/main/resources/static/admin/`
且已被 gitignore，而 `backend/Dockerfile` 直接拷贝 `src` 目录——漏构建时镜像照样构建成功、
容器健康、后端接口全部正常，**只有 `/admin/` 返回 404**，很难第一时间联想到是漏了这一步。
只改后端或小程序时可以跳过；改过 `packages/admin-web/` 后必须重新执行。取舍见
[ADR-0003](docs/adr/0003-admin-web-separate-source-same-origin-runtime.md)。

首次完整启动自动执行 Flyway migration 和幂等 seed，并启动 PostgreSQL、业务 Redis、RSSHub
缓存、RSSHub、Java API、定时 Worker。小程序及 `tests/miniprogram-e2e/` 不进入镜像。

生产发布可由推送 `v*` 版本标签自动触发，也可在 Actions 页面手动运行；普通分支提交和
合并到 `main` 不会触发部署。Actions 构建后端与 RSSHub 私有 GHCR 镜像，并在启用服务器
部署后通过 SSH 更新 Compose 服务。生产 `.env` 仅保存在 GitHub `production`
Environment Secret 和服务器上，配置方法见 `docs/DEPLOYMENT.md`。

微信开发者工具请**导入 `miniprogram/` 目录**作为项目（`project.config.json`/`project.private.config.json` 已随小程序代码放在其中，`miniprogramRoot` 为 `./`）。配置在 [miniprogram/config/env.js](miniprogram/config/env.js)（无密钥，已随仓库提交）：按需修改 `apiBaseUrl` 为可访问的 API 地址，上线前填写订阅消息模板 ID。

默认访问 `http://127.0.0.1:8080`。开发者工具本机 HTTP 调试需关闭合法域名校验；真机/体验版必须使用已加入微信后台合法域名的生产 HTTPS 地址。

开发 RSSHub route：

```bash
git submodule update --init --depth 1
corepack enable pnpm
pnpm install --frozen-lockfile
pnpm run rsshub:install
pnpm run rsshub:dev
```

当前闭环直接复用 RSSHub 上游 `/weibo/user/:uid`：王一博 UID 为 `5492443184`。
RSSHub Cookie、本地启动、seed、Worker 抓取及 PostgreSQL 查询命令见
[RSSHub route 与小程序自动化开发](docs/RSSHUB-DEVELOPMENT.md#王一博微博到-postgresql-闭环)。

完整工作流和小程序自动化边界见
[RSSHub route 与小程序自动化开发](docs/RSSHUB-DEVELOPMENT.md)。

Java 测试：

```bash
mvn -f backend/pom.xml test
mvn -f backend/pom.xml -Pintegration-test -Didolradar.it.enabled=true verify
```

第二条会用 Testcontainers 启动隔离的 PostgreSQL，验证 migration、约束、seed 幂等。可选客户端检查需要 Node.js 24：

```bash
corepack enable pnpm
pnpm install --frozen-lockfile
pnpm test
pnpm run validate
```

安装并运行小程序自动化基础校验：

```bash
pnpm install --frozen-lockfile
pnpm run test:miniprogram:e2e
```

真实 UI 用例必须设置 `WECHAT_CLI_PATH`，并且只通过 `miniprogram-automator` 驱动微信
开发者工具；未设置时相关用例会跳过。

## 定时任务

`worker` 容器常驻，默认每 30 分钟运行。修改 `.env` 的 `WORKER_INTERVAL` 后重建 Worker。
手工立即执行一次：

```bash
docker compose run --rm \
  -e IDOLRADAR_WORKER_SCHEDULE_ENABLED=false \
  worker
```

任务使用 PostgreSQL advisory lock，重叠执行会安全跳过。Post 与通知 outbox 同事务；进程中断后下轮续跑。API 可水平扩容；Redis 统一管理跨实例限流和微信 access token。

## 上线校验

```bash
pnpm run validate:release
```

校验阻止本机 API/RSSHub 地址、缺失服务端密钥、错误 AppID、缺少 Java/Flyway/Redis/容器配置进入发布。完整流程见[部署手册](docs/DEPLOYMENT.md)。

## 文档

- [功能设计](docs/MVP-功能设计.md)
- [产品需求 PRD](docs/MVP-产品需求PRD.md)
- [测试用例](docs/MVP-测试用例.md)
- [部署手册](docs/DEPLOYMENT.md)
