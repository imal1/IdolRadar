# IdolRadar 追星雷达

面向追星族的微信小程序：像拥有一台雷达，实时追踪你喜欢的那一位 idol 的动态。

## 它做什么

关注一位 idol → 系统定时抓取其 RSS 动态源 → 有新动态时通过微信订阅消息推送 → 点推送直达雷达动态流。

## MVP 范围

- 微信静默登录（仅 openid，无注册）
- 从预置 idol 库选择并关注 1 位 idol
- 动态来源：RSS 第三方订阅源（管理员配置）
- 定时抓取、去重入库、动态流展示
- 微信订阅消息推送新动态

暂不包含：动态详情页、微博/抖音 API 直接接入、多 idol 关注、管理端界面、收费功能（数据字段已预留）。

## 技术方案

微信云开发一体化：云函数（`fetchFeeds` / `sendNotify` / `user`）+ 云数据库（`idols` / `sources` / `posts` / `users`）+ 定时触发器 + 订阅消息。免备案、免运维，单人可维护。

## 项目结构

```text
miniprogram/       原生微信小程序（三个页面与公共组件）
cloudfunctions/    user、fetchFeeds、sendNotify 云函数
database/          初始化样例、索引与安全规则
tests/             Node.js 单元与契约测试
scripts/           语法、配置、发布前校验
docs/              PRD、功能设计、测试与部署手册
```

## 本地检查

要求 Node.js 18.18+；云函数线上运行时固定为 Node.js 20.19。

```bash
npm ci
npm run check
```

`npm run check` 允许本地占位配置。正式发布前必须执行：

```bash
npm run validate:release
```

该命令会阻止使用 `touristappid`、空云环境、空订阅消息模板或示例 RSS 源发布。

## 部署

1. 复制 `.env.example` 为 `.env`，填写云环境与订阅模板。
2. 在微信开发者工具中把 `project.config.json` 的 `appid` 改为正式小程序 AppID。
3. 在 `miniprogram/config/env.js` 填写同一云环境 ID 与订阅模板 ID。
4. 创建四个集合、导入正式 idol/RSS 数据、配置索引与安全规则。
5. 按 `user`、`sendNotify`、`fetchFeeds` 顺序部署云函数并确认 30 分钟触发器。
6. 真机完成订阅消息闭环后上传审核。

完整步骤、回滚与上线检查见 [部署手册](docs/DEPLOYMENT.md)。

## 页面

| 页面 | 说明 |
|---|---|
| 选 idol 页 | 首次引导选择关注对象，亦作更换入口 |
| 雷达首页 | 核心页与推送落地页，时间倒序动态流 |
| 我的页 | 关注管理、推送授权管理 |

## 文档

- [功能设计文档（MVP）](docs/MVP-功能设计.md)
- [产品需求文档 PRD（MVP）](docs/MVP-产品需求PRD.md)
- [测试用例文档（MVP）](docs/MVP-测试用例.md)

## 路线图

微博/抖音等平台 API 接入 → 动态详情页 → 多 idol 关注 → 管理端界面 → 收费功能。
