# IdolRadar

## 项目结构（前后端分离）

- `miniprogram/`：微信小程序，**开发者工具直接导入此目录**（`project.config.json` 在内，`miniprogramRoot` 为 `./`）。
- `backend/`：Spring Boot 后端源码与镜像 `Dockerfile`。
- `compose.yaml`、根目录 `.env`：服务器统一 Docker 编排；HTTPS 入口由服务器现有宿主机 Nginx 管理。所有环境变量集中在根目录 `.env`，所有 `docker compose` 命令都在项目根目录执行。
- `rsshub/`：指向 `imal1/RSSHub` 的源码子模块，用于开发、调试、测试和构建自定义 route；官方 `DIYgod/RSSHub` 作为其上游同步来源。
- `packages/test-utils/`：跨测试项目共享的纯 Node.js fixture/path 工具，不包含浏览器或小程序驱动。
- `tests/miniprogram-e2e/`：Vitest 测试编排 + 官方 `miniprogram-automator` 驱动；小程序 UI 调试不得改用 Playwright。
- `database/`：发布 seed；根 `compose.yaml` 用 `./database` 只读挂载。
- `scripts/validate-release.js`：发布配置校验，是「必需文件清单」的权威来源；改动配置路径后同步这里。

## 设计稿（Figma）

本项目 UI 设计稿：https://www.figma.com/design/TZbrL98T28IApjsOkC8aBP

- 涉及 UI 实现、还原设计、查设计规范时，优先通过 Figma MCP（`.mcp.json` 中已配置的 `figma` server）读取上述文件，而不是凭空猜测样式。
- 常用工具：`get_design_context`（取节点结构/样式）、`get_screenshot`（截图对照）、`get_variable_defs`（设计变量/token）。
- 具体页面/组件的节点链接（含 `node-id` 参数）可直接粘贴给 AI 使用。

## 协作者接入

1. 在项目根目录运行 `claude`，首次会提示信任项目级 MCP 配置，选择允许。
2. 运行 `/mcp`，对 `figma` server 完成 OAuth 登录（使用自己的 Figma 账号，需对上述文件有查看权限）。

## 调试规范

- 调试小程序时，**永远只用 `miniprogram-automator`**，不使用其他调试方式。

## 代码注释规范

- 新增或修改 SQL、Java、小程序 JavaScript 中的非平凡业务逻辑时，必须同步补充简洁中文注释。
- 注释重点说明业务目的，以及事务、幂等、并发、重试、安全、兼容性边界背后的原因。
- migration 文件必须写版本目的、部署顺序、关键约束与索引用途。
- 非平凡类、接口、复杂方法必须有职责或契约注释；禁止逐行复述代码、给显而易见语句写废话注释。
