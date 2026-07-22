# IdolRadar

## 设计稿（Figma）

本项目 UI 设计稿：https://www.figma.com/design/TZbrL98T28IApjsOkC8aBP

- 涉及 UI 实现、还原设计、查设计规范时，优先通过 Figma MCP（`.mcp.json` 中已配置的 `figma` server）读取上述文件，而不是凭空猜测样式。
- 常用工具：`get_design_context`（取节点结构/样式）、`get_screenshot`（截图对照）、`get_variable_defs`（设计变量/token）。
- 具体页面/组件的节点链接（含 `node-id` 参数）可直接粘贴给 AI 使用。

## 协作者接入

1. 在项目根目录运行 `claude`，首次会提示信任项目级 MCP 配置，选择允许。
2. 运行 `/mcp`，对 `figma` server 完成 OAuth 登录（使用自己的 Figma 账号，需对上述文件有查看权限）。
