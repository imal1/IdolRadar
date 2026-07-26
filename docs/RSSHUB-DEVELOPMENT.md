# RSSHub route 与小程序自动化开发

## RSSHub 源码子项目

`rsshub/` 是 `DIYgod/RSSHub` 的 Git 子模块，仅用于开发、调试自定义 route。正式统一
编排直接使用 `compose.yaml` 中的 RSSHub 镜像。需要修改上游源码时再初始化子模块：

```bash
git submodule update --init --depth 1
corepack enable pnpm
pnpm install --frozen-lockfile
pnpm run rsshub:install
pnpm run rsshub:dev
```

RSSHub 当前要求 Node.js 22.20+ 或 24，并锁定 pnpm 版本。项目根 `.nvmrc` 使用 Node.js
24；依赖仍由 `rsshub/pnpm-lock.yaml` 独立管理，避免外层项目破坏上游 overrides 与
patched dependencies。

新 route 放在 `rsshub/lib/routes/<namespace>/`，遵循上游当前的 namespace、route、
radar、缓存和测试规范。开发分支应推送到个人 RSSHub fork，再向上游提交 PR：

```bash
git -C rsshub remote add fork https://github.com/<your-account>/RSSHub.git
git -C rsshub switch -c route/<namespace>
```

不要让主仓库引用只存在于本机、尚未推送到远端的 submodule commit，否则其他协作者
无法初始化该版本。

## 王一博微博到 PostgreSQL 闭环

RSSHub 上游已经提供 `/weibo/user/:uid`，无需维护重复的自定义 route。王一博的微博 UID
为 `5492443184`；仓库 seed 保存 `/weibo/user/5492443184`，Java seed 会用
`RSSHUB_BASE_URL` 拼成完整 Feed URL。

本机直接开发 RSSHub 源码时：

```powershell
# 部分微博账号需要登录态。Cookie 只放进当前进程环境，禁止写入 Git。
$Env:WEIBO_COOKIES = '从已登录微博会话复制的 Cookie'
pnpm run rsshub:dev
```

先直接验证 Feed：

```powershell
Invoke-WebRequest http://127.0.0.1:1200/weibo/user/5492443184
```

统一 Docker 链路复制根 `.env.example` 为 `.env`，填写 `WEIBO_COOKIES`。保持
`NOTIFICATIONS_ENABLED=false` 可在没有微信密钥时单独验收 RSS 入库：

```powershell
docker compose up -d --build
docker compose exec rsshub curl -f http://127.0.0.1:1200/weibo/user/5492443184
docker compose exec postgres psql -U idolradar -d idolradar -c `
  "SELECT channel, title, link, published_at FROM posts WHERE idol_id = 'idol_wang_yibo' ORDER BY published_at DESC LIMIT 10;"
```

`RSS_TRUSTED_ORIGINS=http://rsshub:1200` 只为 Docker 私网 RSSHub 精确放开 HTTP/私网
访问；其他 Feed 仍强制公网 HTTPS、DNS pin 和重定向复验。

## 依赖共享边界

根 `pnpm-workspace.yaml` 管理 `packages/*` 和 `tests/miniprogram-e2e`；RSSHub
保留自己的 `pnpm-lock.yaml`，避免外层项目破坏上游 overrides 与 patched
dependencies。两套安装仍会通过 pnpm 的内容寻址存储复用相同版本的 Vitest 等包，
无需把 RSSHub 的 Node.js 服务端依赖暴露给小程序。

可共享内容放在 `packages/test-utils/`，仅允许无 DOM、无微信运行时、无 RSSHub
内部别名的纯 Node.js 工具或 fixture。需要向上游提交的 RSSHub route 测试应保持
自包含，不依赖主仓库私有包。

## 小程序 E2E

小程序 E2E 使用 Vitest 负责编排、断言与报告，实际 UI 驱动始终是微信官方
`miniprogram-automator`。Playwright/Patchright 只供 RSSHub 抓取网页，不连接微信
开发者工具。

安装并执行不需要开发者工具的基础校验：

```bash
pnpm install --frozen-lockfile
pnpm run test:miniprogram:e2e
```

运行真实小程序冒烟测试前，安装微信开发者工具、开启服务端口，并配置 CLI 路径：

```powershell
$Env:WECHAT_CLI_PATH = 'C:\path\to\wechat-devtools\cli.bat'
pnpm run test:miniprogram:e2e
```

未设置 `WECHAT_CLI_PATH` 时，真实开发者工具用例会明确标记为 skipped，纯配置测试
仍会运行。CI 不应伪造该路径；需要真机或开发者工具的作业应使用专门 Windows runner。
