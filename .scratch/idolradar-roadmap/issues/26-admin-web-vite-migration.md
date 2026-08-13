# 26 — 管理端前端迁移到 packages/admin-web（Vite + TS）

**What to build:** 管理端前端从 `backend/src/main/resources/static/admin/` 搬到 `packages/admin-web`，用 Vite 构建、TypeScript 编写，构建产物仍然打进 backend 镜像、与 API 同源。

现在管理端是三个手写文件躺在 backend 的 resources 里：`app.js` 一个文件 796 行、无类型、无模块、无依赖管理、无热更新、无产物指纹。它已经承载了 6 个页面和全部管理端操作，继续按现在的形态长下去，改动成本只会越来越高。

搬走的是**源码**，不是运行时：产物仍与 API 同源，不引入跨源鉴权、不新增部署单元。理由见 ADR 0003。

**这张单不改任何行为。** 样式、token 存储、全量重绘模型、审计页的演示数据全部原样搬。这张单里新加的 Vitest 覆盖不到渲染，红线是唯一能证明「构建方式变了、行为没变」的保障。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `packages/admin-web` 接入 pnpm workspace，Vite + TypeScript（`strict`）可构建可 dev
- [ ] 原 `app.js` 拆成 `main.ts` / `api.ts` / `state.ts` / `pages/*.ts` / `ui.ts`，管理端 API 响应有手写类型
- [ ] dev server 把 `/admin/v1` 代理到本机后端，`base` 与生产一致为 `/admin/`
- [ ] 构建产物落到 backend 静态资源路径并被 gitignore，jar 里的管理端页面与迁移前等价
- [ ] CI 在构建镜像前构建前端，`scripts/validate-release.js` 的必需文件清单能挡住产物缺失
- [ ] `backend/src/main/resources/static/admin/` 的三个源文件被删除，仓库内不存在两份管理端源码
- [ ] `typecheck` 与管理端 Vitest 进入 `pnpm check`，本地一条命令与 CI 结果一致
- [ ] ADR 0003 记录「源码独立、产物同源」的取舍，`CONTEXT.md` 的项目结构段同步更新
