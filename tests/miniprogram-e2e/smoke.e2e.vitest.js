import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { resolveExistingPath } from '@idolradar/test-utils';
import automator from 'miniprogram-automator';
import { afterAll, beforeAll, describe, expect, test } from 'vitest';

const here = dirname(fileURLToPath(import.meta.url));
const cliPath = process.env.WECHAT_CLI_PATH?.trim();
const describeWithDevTools = cliPath ? describe : describe.skip;
let miniProgram;

describeWithDevTools('微信小程序冒烟测试', () => {
  beforeAll(async () => {
    const projectPath = resolveExistingPath(here, '..', '..', 'miniprogram');
    // UI 操作只通过微信官方自动化协议执行；Vitest 仅负责编排、断言和报告。
    miniProgram = await automator.launch({
      cliPath,
      projectPath,
    });
  });

  afterAll(async () => {
    await miniProgram?.close();
  });

  test('可重新打开选择页并识别页面根节点', async () => {
    const page = await miniProgram.reLaunch('/pages/picker/index');
    await page.waitFor(500);

    expect(page.path).toBe('pages/picker/index');
    const shell = await page.$('.picker-page');
    expect(shell).not.toBeNull();
    expect(await shell.attribute('class')).toContain('picker-page');
  });
});

// 保留一个无需开发者工具即可执行的用例，让依赖安装和测试发现能在普通 CI 中被验证。
test('默认小程序项目目录可解析', () => {
  expect(resolve(here, '..', '..', 'miniprogram')).toMatch(/[\\/]miniprogram$/);
});
