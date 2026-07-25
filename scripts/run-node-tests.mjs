import { readdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { spawnSync } from 'node:child_process';

const projectRoot = resolve(import.meta.dirname, '..');
const testDirectories = [
    resolve(projectRoot, 'tests'),
    resolve(projectRoot, 'packages', 'test-utils'),
];

// 只把本仓库维护的 Node 测试交给内置测试器，避免递归进入 RSSHub 子模块。
const testFiles = testDirectories.flatMap((directory) =>
    readdirSync(directory)
        .filter((file) => file.endsWith('.test.js'))
        .map((file) => resolve(directory, file))
);

const result = spawnSync(process.execPath, ['--test', ...testFiles], {
    cwd: projectRoot,
    stdio: 'inherit',
});

if (result.error) {
    throw result.error;
}

process.exitCode = result.status ?? 1;
