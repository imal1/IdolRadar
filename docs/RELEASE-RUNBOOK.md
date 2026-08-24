# 发版操作手册

按版本标签发布到生产的完整步骤。日常部署与配置说明见 [DEPLOYMENT.md](DEPLOYMENT.md)，本文只覆盖「打标签发版」这条路径。

## 0. 发版会做什么

推送 `v*` 标签（或在 Actions 页面手动运行 `release`）触发 `.github/workflows/release.yml`，两个 Job 串行：

| Job | 动作 |
|---|---|
| `publish` | 构建管理端前端 → 构建并推送 backend 与 RSSHub 私有 GHCR 镜像（同时打 `latest`） |
| `deploy` | 仅当仓库变量 `DEPLOY_ENABLED=true`；组装发布包 → SSH 传到服务器 → `docker compose pull && up -d` |

发布包只含 `compose.yaml`、`database/` 与由 `PRODUCTION_ENV_FILE` Secret 生成的 `.env`，并把镜像标签追加写进 `.env`。**不含源码**，服务器上不执行任何构建。

普通提交和合并到 `main` 不会触发发版。

`release.yml` 不跑测试也不跑发布校验——那是 `ci.yml` 的职责。因此发版前必须确认目标提交的 CI 是绿的。

## 1. 发版前检查

### 1.1 确认 CI 绿

```bash
gh run list --repo imal1/IdolRadar --branch main --limit 3
```

红的不要发。`release.yml` 不会替你拦。

### 1.2 确认本次发布包含什么

```bash
git fetch origin --tags
git log --oneline $(git describe --tags --abbrev=0 origin/main)..origin/main
```

空的说明相对上一个标签没有新提交，没有发版的必要。

### 1.3 确认生产数据库的 Flyway 版本

**这一步决定本次发版是否涉及 DDL，不能跳过。** 在服务器上：

```bash
cd /opt/idolradar
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
  "SELECT version, description, success, installed_on
   FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 5"
```

把结果与 `backend/src/main/resources/db/migration/` 下的文件比对，确认本次会执行哪些新版本。

### 1.4 备份数据库

有 DDL 变更时必做；没有也建议做。

```bash
docker compose exec -T postgres \
  pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > /opt/idolradar-backup-$(date +%Y%m%d-%H%M).dump
```

含**不可逆**迁移（如 `DROP COLUMN`）时，备份是唯一的回滚手段——Flyway 没有回滚脚本。

### 1.5 确认生产有管理员账号

没有的话管理端登不进去。查：

```bash
docker compose exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc \
  "SELECT username, enabled FROM idr_admin_account"
```

为空则按 [DEPLOYMENT.md 5.1](DEPLOYMENT.md) 创建。

### 1.6 确认订阅消息配置

模板 ID 与三个字段序号写在 `compose.yaml` 里（不在 `.env`）。字段序号以微信公众平台的模板详情为准，**不要假定是 `thing1/thing2/time3`**。同时确认 `.env` 里 `NOTIFICATIONS_ENABLED=true`，否则 Worker 一条都不会发。

## 2. 含不可逆迁移时的部署顺序

`release.yml` 的部署脚本是：

```bash
docker compose pull
docker compose up -d --no-build --remove-orphans
```

**没有先停旧容器。** 当本次发版包含删列这类破坏性迁移时，会出现一个数秒的窗口：新的 `migrate` 已经删掉列，旧的 `app`/`worker` 容器还在写它，期间相关接口返回 500。

规避方式是在打标签**之前**先手工停掉服务，让流水线的 `up -d` 从停止状态起：

```bash
cd /opt/idolradar
docker compose stop app worker
```

标签推送、流水线跑完后容器会重新起来。纯代码变更（无新迁移）不需要这一步。

判断依据：把 1.3 查到的版本号与仓库里的迁移文件对比，逐个读新版本文件头的「不可逆说明」和「部署顺序」注释——项目规范要求每个迁移都写明这两项。

## 3. 打标签发版

```bash
git checkout main
git pull --ff-only
git tag v0.1.0-rc.4
git push origin v0.1.0-rc.4
```

标签命名沿用 `v<major>.<minor>.<patch>` 或带 `-rc.N` 后缀的预发布。**核心链路未在生产验证过时用 `-rc.N`**，给自己留「发上去发现问题再修」的余地，不要一上来烧掉 `1.0.0`。

只想验证镜像能正常构建、不碰服务器时，先关掉部署：

```bash
gh variable set DEPLOY_ENABLED --repo imal1/IdolRadar --body false
```

发完再改回 `true`。

跟踪进度：

```bash
gh run watch --repo imal1/IdolRadar
```

## 4. 上线后验收

### 4.1 服务可用

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://你的域名/readyz
```

在服务器上确认容器状态：`migrate` 与 `seed` 应为 `Exited (0)`，其余为 `Up`/`healthy`。

```bash
docker compose ps -a
```

`migrate` 或 `seed` 是非 0 退出码时，`app` 不会启动，先看日志：

```bash
docker compose logs migrate seed --tail=100
```

### 4.2 管理端入口

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://你的域名/admin
curl -s -o /dev/null -w '%{http_code}\n' https://你的域名/admin/
```

两者都应为 200（`/admin` 经 Nginx 会 301 到 `/admin/`，`curl -L` 跟随后为 200）。

**后端接口全部正常而只有 `/admin/` 404，说明构建漏了管理端产物。** 发布流水线已包含该步骤，出现这个症状要查 `publish` job 的「Build admin frontend」步骤。

### 4.3 安全响应头与缓存

```bash
curl -sI https://你的域名/admin/ | grep -iE \
  'content-security-policy|x-frame-options|referrer-policy|x-content-type-options|x-robots-tag|cache-control'
```

预期入口 HTML 为 `Cache-Control: no-store`、`X-Frame-Options: DENY`、`X-Robots-Tag: noindex, nofollow`；`/admin/assets/` 下的指纹产物为 `public, max-age=31536000, immutable`。同名头出现两条说明宿主机 Nginx 有冲突的全局 `add_header`，见 `deploy/nginx/admin.conf` 文件头说明。

### 4.4 MVP 五步闭环（真机）

这是唯一无法用命令验证的部分，需要真机操作：

1. 全新用户首次打开小程序即可使用，无注册步骤
2. 从 idol 库选择并守护一位，返回雷达首页看到该 idol
3. 点「去开启」完成订阅授权，蹲守提醒 banner 相应隐藏
4. 收到微信订阅消息——两种触发方式任选：
   - 等 Worker 定时轮次自然抓到新动态（默认 30 分钟）
   - 管理端「推送投递看板」→「定向推送」，选中自己的账号立即发一条
5. 点击消息落到雷达首页并定位到对应动态，**冷启动与热启动都要试**

第 5 步的热启动路径尚未验证过：`app.js` 没有启动参数处理，`radar` 页 `onLoad` 是唯一的上报触发点，小程序已在后台时点推送是否仍走 `onLoad` 需要实测确认。

### 4.5 回访率

完成 4.4 第 5 步后，管理端「核心指标」页的「推送回访率」应从 `0%` 变为非 0，「推送投递」看板的对应投递记录应出现回访时间。

两个页面的口径不同，数字不一致是预期的：核心指标页限定「送达后 24 小时内打开」，投递看板不设时间窗。

### 4.6 边界

- 更换 idol 后旧动态不再展示、旧推送停止
- 订阅额度为 0 时不发送
- 空动态流展示正常
- 重跑一轮 Worker：不重复入库、不重复推送

## 5. 回滚

### 5.1 只有代码变更

切回上一个标签重新发版即可。镜像仍在 GHCR，也可以直接在服务器上改 `.env` 里的 `IDOLRADAR_IMAGE` / `RSSHUB_IMAGE` 指回旧标签，然后：

```bash
docker compose pull && docker compose up -d --no-build
```

### 5.2 含不可逆迁移

**代码回滚不会自动恢复数据库。** Flyway 采用前向修复，不删除已执行版本，也没有回滚脚本。

含 `DROP COLUMN` 的版本执行后，回滚需要：

1. 从备份恢复数据库（1.4 那份 dump），或
2. 手工重建被删的列——`ADD COLUMN` + 从关联表回填 + 重建索引与外键，且回滚窗口内不得有新的业务写入，否则重建结果与真实数据不一致

因此含不可逆迁移的发版必须先备份，且尽量安排在低流量窗口。

### 5.3 禁止的操作

```bash
docker compose down -v   # 会删除 PostgreSQL 与 Redis 命名卷，业务数据全丢
```

## 6. 密钥与配置的存放位置

| 内容 | 存放位置 |
|---|---|
| 生产 `.env` 全文 | GitHub `production` Environment 的 `PRODUCTION_ENV_FILE` Secret，以及服务器 `/opt/idolradar/.env` |
| 订阅消息模板 ID 与字段序号 | `compose.yaml`（随仓库提交；模板 ID 不属于密钥） |
| 小程序侧模板 ID | `miniprogram/config/env.js`（随仓库提交，必须与服务端一致） |
| 微信 AppSecret、数据库与 Redis 密码、RSSHub Cookie | 只在 `.env`，不进数据库、管理页面和 Git |
| 部署 SSH 私钥与 known_hosts | GitHub Secret `DEPLOY_SSH_KEY` / `DEPLOY_KNOWN_HOSTS` |

更新生产 `.env`：

```bash
gh secret set PRODUCTION_ENV_FILE --repo imal1/IdolRadar --env production < 你的生产.env
```

流水线每次发版都会用该 Secret 覆盖服务器上的 `.env`，因此在服务器上手改 `.env` 会在下次发版被覆盖——改动要回写到 Secret。
