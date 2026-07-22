# IdolRadar 部署与上线手册

本文用于微信小程序测试、生产环境。真实 AppID、云环境 ID、订阅消息模板 ID、RSS 源不入库；发布前由管理员写入本地配置并复核。

## 1. 发布前准备

需要：

- 已认证、可发布的小程序账号及管理员权限
- 微信开发者工具稳定版
- 已开通的小程序云开发环境；测试、生产环境分离
- 已申请的一次性订阅消息模板
- 已获授权的 idol 头像、资料、公开 RSS 源
- 至少两个真机测试微信号

先执行离线检查：

```bash
npm ci
npm run check
```

`--allow-placeholders` 只适合代码检查。真实配置与种子替换完成后，必须执行不带该选项的发布校验：

```bash
node scripts/validate-project.js --seed-dir /绝对路径/production-seeds
```

发布校验仍失败时禁止提审。

## 2. AppID、云环境、模板配置

1. 将 `project.config.json` 的 `appid` 从 `touristappid` 改为真实小程序 AppID。
2. 在微信开发者工具中开通或选择生产云环境，记录环境 ID。
3. 将 `cloudbaserc.json` 与 `miniprogram/config/env.js` 的云环境 ID 改为同一生产环境。
4. 在微信公众平台申请一次性订阅消息模板。模板至少包含 idol 名、动态标题、发布时间；字段必须与 `cloudfunctions/sendNotify/lib/message.js` 的 `buildSubscribeData()` 一致。
5. 将模板 ID 写入 `miniprogram/config/env.js`，并通过 `.env` 或部署平台环境变量解析 `cloudbaserc.json` 中的 `SUBSCRIBE_TEMPLATE_ID`。客户端、服务端必须一致。
6. 测试环境使用独立 AppID/云环境/模板配置，不得连接生产集合。
7. 生产头像若使用外部 CDN，须在小程序后台配置对应的 `downloadFile` 合法域名；也可改用同一云环境的云存储地址。真机验证头像加载与失败降级。

不要把 AppSecret、访问令牌或微信用户数据写入仓库。云函数通过运行环境身份调用微信能力。

## 3. 建集合、权限、索引

在目标云环境创建四个集合：`idols`、`sources`、`posts`、`users`。

四个集合的权限均设为“所有用户不可读写”或等价自定义规则。小程序不能直接读写数据库；所有读写必须经过 `user`、`fetchFeeds`、`sendNotify` 云函数。云函数管理端 SDK 不受客户端规则影响。

将 `database/security-rules.json` 中每个集合对应的 `read/write` 规则分别粘贴到该集合安全规则；将 `database/function-security-rules.json` 应用到环境级云函数权限控制。`user` 仅允许非匿名的已认证客户端调用；`fetchFeeds`、`sendNotify` 禁止客户端调用。定时触发器、管理端调用不受函数安全规则影响。

按 `database/indexes.json` 在云开发控制台逐项创建：

- `posts.link`：唯一索引，最终去重防线
- `users.openid`：唯一索引，防止重复建档
- `posts.idolId + publishedAt desc + _id desc`：动态流稳定游标分页
- `users.idolId + subscribeQuota + _id`：推送目标稳定分页
- `sources.enabled + _id`：定时抓取稳定分页
- `sources.idolId + _id`、`sources.idolId + enabled + _id`：idol 信号源查询
- `idols.enabled + _id`：启用 idol 稳定分页；`idols.enabled + name` 用于名称排序

唯一索引创建失败通常表示已有重复数据。先导出备份、定位重复记录；不要直接删除生产数据。

## 4. 替换并导入种子

`database/idols.seed.jsonl`、`database/sources.seed.jsonl` 使用 `example.invalid`，故意不能联网。CloudBase 导入要求 UTF-8 JSON Lines：每行一个完整 JSON 对象，不是 JSON 数组。导入前：

1. 复制两份文件到本地未跟踪目录，例如 `/private/tmp/idolradar-production-seeds/`，保留原文件名。
2. 替换名称、头像、简介、RSS URL；确认资料与来源授权。
3. 保持 `sources.idolId` 与对应 `idols._id` 完全一致。
4. 所有 RSS URL 必须为公网 `https` 地址；禁止 localhost、内网 IP、云元数据地址、带账号密码 URL。发布校验检查 URL 字面值，云函数每次请求和重定向时再校验 DNS 解析结果。
5. 在云开发控制台导入 `idols`，再导入 `sources`。
6. 抽查启用状态、关联 ID、RSS 响应、XML 编码。
7. 运行 `node scripts/validate-project.js --seed-dir /private/tmp/idolradar-production-seeds`。校验器会逐行验证 JSON、关联 ID、HTTPS URL、配置占位。生产导入使用这份已校验副本；仓库样例保持不可联网。

## 5. 安装依赖与部署云函数

每个函数的 `package.json` 使用精确依赖版本。需本地验证时安装生产依赖；不要把本机 `node_modules` 上传到 Git：

```bash
npm run install:functions
```

使用 `.nvmrc` 对应的 Node.js 20 执行部署前检查，避免本地运行时与云端 `Nodejs20.19` 不一致。

截至 2026-07-22，最新 `wx-server-sdk@4.0.2` 固定的 CloudBase 传递依赖会被 `npm audit` 报告 1 个中危、5 个高危；官方依赖链尚无兼容修复版本。RSS 网络请求未使用其中的 axios，业务代码也未把客户端输入当作数据库字段路径，但这不等于风险消失。上线负责人必须记录风险接受，持续检查官方 SDK 更新；不要执行会把 SDK 强制降级到旧主版本的 `npm audit fix --force`。

使用微信开发者工具“上传并部署：云端安装依赖”，顺序：

1. `user`
2. `sendNotify`
3. `fetchFeeds`

`fetchFeeds` 最后部署，因为它依赖集合、索引并调用推送链路。每次部署后在云函数控制台确认运行时版本、环境变量、依赖安装结果。

先手动调用：

- `user`：首访建档、查询启用 idol、关注切换、订阅额度、动态分页
- `sendNotify`：只在测试用户已授权、测试集合已有数据时调用
- `fetchFeeds`：先仅启用一个可控测试源，确认解析、去重、状态写回

禁止从本手册或自动测试直接请求外部云环境；云端集成测试由管理员在目标环境手动执行。

## 6. 每 30 分钟触发器

`cloudfunctions/fetchFeeds/config.json` 应包含 timer：

```json
{
  "triggers": [
    {
      "name": "fetchFeedsEvery30Minutes",
      "type": "timer",
      "config": "0 */30 * * * * *"
    }
  ]
}
```

`cloudbaserc.json` 与函数目录 `config.json` 使用相同触发器名称和表达式，避免不同部署工具创建重复定时器。部署 `fetchFeeds` 时同步配置；在控制台确认只存在一个已启用触发器、时区符合预期。先手动运行一次；再等待完整 30 分钟周期，检查函数日志、`sources.lastFetchAt`、`sources.lastFetchStatus`、`posts` 去重结果。

## 7. 真机闭环验收

开发者工具不能验证真实订阅消息到达。体验版至少执行：

1. 微信号 A 首次打开；确认静默登录、`users.openid` 唯一建档、无守护时进入选择页。
2. 选择 idol；允许订阅消息；确认 `idolId`、`subscribeQuota`、`subscribedAt`。
3. 用可控 RSS 增加一条唯一链接，手动运行 `fetchFeeds`。
4. 确认 `posts` 新增一条，source 状态成功；A 收到一条消息且额度只减 1。
5. 点击消息；确认落到 `pages/radar/index` 并看到最新动态。
6. 同轮增加三条；确认每用户最多一条消息。
7. 微信号 B 守护另一 idol；确认不收到 A 所守护 idol 的消息。
8. 将 A 额度设为 0；确认不发送、不扣成负数。
9. 更换守护对象；确认旧动态不展示、旧 idol 不再推送。
10. 覆盖坏 XML、404、超时、缺摘要、缺发布时间、重复链接；确认单源失败不阻塞其他源。

记录用例 ID、时间、微信号、云环境、函数 request ID、截图。`docs/MVP-测试用例.md` 的 `E2E-01` 未通过则不得上线。

## 8. 日志、监控、回滚

上线前保存当前可用体验版、云函数版本、配置与数据库导出。上线后首日重点检查：

- `fetchFeeds` 每 30 分钟有成功日志，无整轮中断
- `sources.lastFetchStatus` 连续失败源及其 HTTP/解析原因
- `posts.link` 唯一冲突只表现为安全跳过，无重复数据
- 订阅发送成功/失败数、失败码、额度扣减是否仅发生在发送成功后
- `user` 错误率、分页耗时、未授权数据库访问

回滚顺序：

1. 严重误推送或抓取异常时，先禁用 `fetchFeeds` 定时触发器。
2. 回滚小程序到上一审核版本；云函数逐个切回上一可用版本。
3. 恢复上一版环境变量与模板配置。
4. 数据库变更先导出现场，再按唯一链接或 request ID 精确修复；禁止无备份批量删除。
5. 手动验证登录、动态流、单次测试推送后再恢复触发器。

## 9. 隐私与提审清单

- [ ] 隐私保护指引说明静默 openid、订阅消息、动态链接用途
- [ ] 不采集手机号；`phone`、`billing` 占位字段为空
- [ ] 不存 AppSecret、access token、Cookie、测试 openid、生产导出
- [ ] idol 头像、名字、简介、RSS 内容与来源标签具备合法使用依据
- [ ] 仅存标题、摘要、原文链接；复制链接行为有明确提示
- [ ] 数据库禁止客户端直读写；RSS URL 通过公网/SSRF 校验
- [ ] 小程序类目、名称、图标、介绍、截图、服务条款已配置
- [ ] 体验版完成 `E2E-01` 与 P0 用例；两名测试用户签字
- [ ] 不带 `--allow-placeholders` 的发布校验通过；版本号、变更说明、隐私声明一致
- [ ] 已准备回滚版本、数据备份、值班联系人、日志检查窗口

发布审核通过后再开启生产 `fetchFeeds` 触发器，避免审核期间产生真实推送。

## 10. 官方参考

- [CloudBase 数据库导入/导出（JSON Lines）](https://docs.cloudbase.net/database/manage)
- [CloudBase 数据库安全规则](https://docs.cloudbase.net/rule/introduce)
- [CloudBase 云函数安全规则](https://docs.cloudbase.net/en/cloud-function/security-rules)
