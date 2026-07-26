import { loadEnvFile } from 'node:process';
import { fileURLToPath } from 'node:url';

import { defineConfig } from 'vitest/config';

try {
  // E2E 与后端、RSSHub 共用仓库根目录配置，已有进程环境变量保持更高优先级。
  loadEnvFile(fileURLToPath(new URL('../../.env', import.meta.url)));
} catch (error) {
  if (error?.code !== 'ENOENT') throw error;
}

export default defineConfig({
  test: {
    include: ['**/*.e2e.vitest.js'],
    fileParallelism: false,
    maxWorkers: 1,
    hookTimeout: 60_000,
    testTimeout: 30_000,
  },
});
