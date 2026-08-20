'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const apiPath = require.resolve('../miniprogram/utils/api');
const pagePath = require.resolve('../miniprogram/pages/radar/index');

function loadPage(callUser) {
  const originalApiModule = require.cache[apiPath];
  const originalPage = global.Page;
  const originalWx = global.wx;
  let definition;

  require.cache[apiPath] = {
    id: apiPath,
    filename: apiPath,
    loaded: true,
    exports: { callUser: callUser }
  };
  global.Page = function (options) { definition = options; };
  global.wx = {
    getStorageSync: function () { return ''; },
    setStorageSync: function () {},
    onThemeChange: function () {},
    offThemeChange: function () {},
    getSystemInfoSync: function () { return {}; },
    showToast: function () {}
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

  const page = Object.assign({}, definition, {
    data: Object.assign({}, definition.data),
    setData: function (changes) { Object.assign(page.data, changes); },
    // 首屏加载不是本用例的关注点，替换成空实现以隔离上报逻辑。
    loadInitial: function () { return Promise.resolve(); }
  });
  return page;
}

test('landing from a push reports the open exactly once', () => {
  const calls = [];
  const page = loadPage((action, payload) => {
    calls.push([action, payload]);
    return Promise.resolve({ recorded: true });
  });

  page.onLoad({ postId: 'post-1' });

  assert.deepEqual(calls, [['reportNotificationOpen', { postId: 'post-1' }]]);
  assert.equal(page.data.deepLinkPostId, 'post-1');
});

test('a normal open is never counted as a push return visit', () => {
  const calls = [];
  const page = loadPage((action, payload) => {
    calls.push([action, payload]);
    return Promise.resolve({});
  });

  page.onLoad({});
  page.onLoad(undefined);

  // 没有 postId 就不是从推送落地，一次请求都不该发出。
  assert.deepEqual(calls, []);
  assert.equal(page.data.deepLinkPostId, '');
});

test('a failed report never breaks the page', async () => {
  const page = loadPage(() => Promise.reject(new Error('上报失败')));

  // onLoad 内部吞掉上报异常；这里若有未捕获的 rejection，测试进程会失败。
  page.onLoad({ postId: 'post-1' });
  await new Promise((resolve) => setImmediate(resolve));

  assert.equal(page.data.deepLinkPostId, 'post-1');
});
