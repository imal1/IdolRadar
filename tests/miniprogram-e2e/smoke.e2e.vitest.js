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

  test('推送落地会继续分页并定位对应动态', async () => {
    // 阻止页面启动请求干扰场景数据；页面方法和滚动仍由官方自动化协议真实执行。
    await miniProgram.evaluate(function () {
      getApp().ensureBootstrap = function () {
        return new Promise(function () {});
      };
    });
    const page = await miniProgram.reLaunch('/pages/radar/index?postId=post-25');
    const firstPage = Array.from({ length: 20 }, (_, index) => ({
      id: `post-${index + 1}`,
      title: `动态 ${index + 1}`,
    }));
    await page.setData({
      loading: false,
      allPosts: firstPage,
      latestPost: firstPage[0],
      previousPosts: firstPage.slice(1),
      hasMore: true,
      nextCursor: 'cursor-20',
      deepLinkPostId: 'post-25',
    });
    await miniProgram.evaluate(function () {
      const current = getCurrentPages()[0];
      current.loadMore = function () {
        const nextPage = Array.from({ length: 10 }, function (_, index) {
          const number = index + 21;
          return { id: 'post-' + number, title: '动态 ' + number };
        });
        const posts = this.data.allPosts.concat(nextPage);
        this.setData({
          allPosts: posts,
          latestPost: posts[0],
          previousPosts: posts.slice(1),
          hasMore: false,
          nextCursor: null,
          loadingMore: false,
        });
        return Promise.resolve();
      };
    });

    await page.callMethod('scrollToDeepLink');
    await page.waitFor(500);

    expect(await page.$('#post-post-25')).not.toBeNull();
    expect(Number(await page.scrollTop())).toBeGreaterThan(0);
  });
});

// 保留一个无需开发者工具即可执行的用例，让依赖安装和测试发现能在普通 CI 中被验证。
test('默认小程序项目目录可解析', () => {
  expect(resolve(here, '..', '..', 'miniprogram')).toMatch(/[\\/]miniprogram$/);
});
