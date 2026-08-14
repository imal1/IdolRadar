---
status: accepted
---

# 管理端源码独立、运行时与 API 同源

管理端前端源码从 `backend/src/main/resources/static/admin/` 移到 `packages/admin-web`，用 Vite + TypeScript 构建；构建产物写回 `backend/src/main/resources/static/admin/`，随 backend 镜像发布，与 `/admin/v1/**` 保持同源。

搬走的是源码，不是运行时。同源意味着管理端仍然只靠 `Authorization: Bearer` 头访问同一站点的 API：不需要 CORS 配置，不需要为管理端单独发一套跨源凭据，也不新增部署单元、反向代理规则或证书。否决「管理端作为独立站点部署」的方案：它把一次发版拆成两次、把鉴权从同源问题变成跨源问题，而管理员规模只有个位数，换不回任何收益。

代价是构建顺序有了硬约束：产物目录被 gitignore，镜像构建上下文又是 `./backend`，所以 CI 与 release 必须在 `docker build` 之前执行 `pnpm run admin:build`。`scripts/validate-release.js` 把产物入口 `backend/src/main/resources/static/admin/index.html` 列入必需文件，用来兜住漏构建——漏了的表现是后端接口全部正常、只有 `/admin/` 404，不设这道检查很难第一时间定位。

`packages/admin-web` 不引入前端框架。管理端是全量重绘的表格与弹窗，现有渲染模型够用；引入框架会带来状态同步模型和一整套构建约定，而这一版要解决的是「无类型、无模块、无依赖管理」，不是渲染方式。
