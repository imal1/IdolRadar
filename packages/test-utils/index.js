import { existsSync } from 'node:fs';
import { resolve } from 'node:path';

/**
 * 解析仓库内测试资源，并在启动外部调试器前给出明确错误，避免把路径问题误判为驱动故障。
 */
export function resolveExistingPath(baseDirectory, ...segments) {
  const target = resolve(baseDirectory, ...segments);
  if (!existsSync(target)) {
    throw new Error(`测试路径不存在：${target}`);
  }
  return target;
}

/** 读取由开发者本机注入的测试配置，仓库不保存微信开发者工具的机器相关路径。 */
export function requireEnvironment(name, environment = process.env) {
  const value = environment[name]?.trim();
  if (!value) {
    throw new Error(`缺少环境变量 ${name}`);
  }
  return value;
}
