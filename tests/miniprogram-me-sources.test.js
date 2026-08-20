'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const apiPath = require.resolve('../miniprogram/utils/api');
const pagePath = require.resolve('../miniprogram/pages/me/index');

// 用与 bootstrap 测试相同的手法接管 Page 与 api 模块，避免真机依赖。
function loadPage(callUser) {
  const originalApiModule = require.cache[apiPath];
  const originalPage = global.Page;
  const originalWx = global.wx;
  const toasts = [];
  let definition;

  require.cache[apiPath] = {
    id: apiPath,
    filename: apiPath,
    loaded: true,
    exports: { callUser: callUser }
  };
  global.Page = function (options) {
    definition = options;
  };
  global.wx = {
    showToast: function (options) { toasts.push(options); },
    getStorageSync: function () { return ''; },
    setStorageSync: function () {},
    onThemeChange: function () {},
    offThemeChange: function () {},
    getSystemInfoSync: function () { return {}; }
  };

  delete require.cache[pagePath];
  require(pagePath);

  if (originalApiModule) {
    require.cache[apiPath] = originalApiModule;
  } else {
    delete require.cache[apiPath];
  }
  global.Page = originalPage;
  global.wx = originalWx;

  // 极简的 setData：只做浅合并，够验证开关逻辑。
  const page = Object.assign({}, definition, {
    data: Object.assign({}, definition.data),
    setData: function (changes) {
      Object.assign(page.data, changes);
    }
  });
  // wx.showToast 在方法执行期间才会被调用，需要保留桩。
  page.__toasts = toasts;
  page.__installWx = function () {
    global.wx = { showToast: function (options) { toasts.push(options); } };
  };
  return page;
}

test('source list normalizes ids and flags every source muted state', async () => {
  const page = loadPage(() => Promise.resolve({
    sources: [
      { _id: 'source-self', displayName: '示例 · 微博', channel: '微博', muted: false },
      { _id: 'source-fans', displayName: '示例后援会 · 微博', channel: '微博', muted: true },
      { displayName: '没有 id 的脏数据' },
      null
    ]
  }));

  await page.loadSources();

  assert.equal(page.data.sourcesLoading, false);
  assert.deepEqual(page.data.sources.map((source) => source.id), ['source-self', 'source-fans']);
  assert.deepEqual(page.data.sources.map((source) => source.muted), [false, true]);
  // 还有开着的来源，就不该提示「全部关闭」。
  assert.equal(page.data.allMuted, false);
});

test('turning off every source raises the all-muted warning', async () => {
  const page = loadPage(() => Promise.resolve({
    sources: [{ _id: 'source-1', displayName: '示例 · 微博', muted: true }]
  }));

  await page.loadSources();

  assert.equal(page.data.allMuted, true);
});

test('toggling a source updates immediately and keeps the state on success', async () => {
  const calls = [];
  const page = loadPage((action, payload) => {
    calls.push([action, payload]);
    return Promise.resolve({});
  });
  page.data.sources = [{ id: 'source-1', displayName: '示例 · 微博', muted: false, pending: false }];
  page.__installWx();

  // switch 的 checked 表示「接收」，关闭时 detail.value 为 false。
  const pending = page.toggleSource({ currentTarget: { dataset: { id: 'source-1' } }, detail: { value: false } });
  assert.equal(page.data.sources[0].muted, true, '请求未完成时界面就要反映用户的选择');
  await pending;

  assert.deepEqual(calls, [['muteSource', { sourceId: 'source-1' }]]);
  assert.equal(page.data.sources[0].muted, true);
  assert.equal(page.data.sources[0].pending, false);
  assert.equal(page.data.allMuted, true);
});

test('a failed toggle rolls back to the previous state and tells the user', async () => {
  const page = loadPage(() => Promise.reject(Object.assign(new Error('网络开小差'), { code: 'NETWORK' })));
  page.data.sources = [{ id: 'source-1', displayName: '示例 · 微博', muted: false, pending: false }];
  page.__installWx();

  await page.toggleSource({ currentTarget: { dataset: { id: 'source-1' } }, detail: { value: false } });

  // 回滚是关键：不回滚的话界面显示已关闭，服务端却仍在推送。
  assert.equal(page.data.sources[0].muted, false);
  assert.equal(page.data.sources[0].pending, false);
  assert.equal(page.data.allMuted, false);
  assert.deepEqual(page.__toasts.map((toast) => toast.title), ['网络开小差']);
});

test('re-entering a toggle while it is in flight is ignored', async () => {
  let resolveCall;
  const page = loadPage(() => new Promise((resolve) => { resolveCall = resolve; }));
  page.data.sources = [{ id: 'source-1', displayName: '示例 · 微博', muted: false, pending: false }];
  page.__installWx();

  const first = page.toggleSource({ currentTarget: { dataset: { id: 'source-1' } }, detail: { value: false } });
  const second = page.toggleSource({ currentTarget: { dataset: { id: 'source-1' } }, detail: { value: true } });

  assert.equal(second, undefined, '请求进行中时重复触发应被忽略，避免连点把状态打乱');
  resolveCall({});
  await first;
  assert.equal(page.data.sources[0].muted, true);
});
