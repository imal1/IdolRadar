import { defineConfig } from 'vitest/config';

// 管理端源码独立，但运行时仍与 API 同源：产物直接写进 backend 静态资源目录，随 backend 镜像发布。
// 取舍见 docs/adr/0003-admin-web-separate-source-same-origin-runtime.md。
export default defineConfig({
  base: '/admin/',
  build: {
    outDir: '../../backend/src/main/resources/static/admin',
    // 产物目录在项目根之外，必须显式允许清空，否则改名后的旧指纹资源会一直堆积。
    emptyOutDir: true,
  },
  server: {
    // dev server 只托管前端；管理端 API 代理到本机后端，保持与生产一致的同源请求路径。
    proxy: {
      '/admin/v1': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
});
