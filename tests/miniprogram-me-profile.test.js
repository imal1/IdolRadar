'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const apiPath = require.resolve('../miniprogram/utils/api');
const pagePath = require.resolve('../miniprogram/pages/me/index');

function loadPage(callUser, bootstrap) {
  const originalApiModule = require.cache[apiPath];
  const originalPage = global.Page;
  const originalWx = global.wx;
  const originalGetApp = global.getApp;
  const calls = [];
  const toasts = [];
  let definition;
  const app = {
    globalData: { bootstrap: bootstrap || null },
    ensureBootstrap: function () { return Promise.resolve(this.globalData.bootstrap); },
    invalidateBootstrap: function () { calls.push(['invalidateBootstrap']); }
  };

  require.cache[apiPath] = {
    id: apiPath,
    filename: apiPath,
    loaded: true,
    exports: {
      callUser(action, payload) {
        calls.push([action, payload]);
        return callUser(action, payload);
      }
    }
  };
  global.Page = function (options) { definition = options; };
  global.getApp = function () { return app; };
  global.wx = {
    showToast: function (options) { toasts.push(options); },
    redirectTo: function () {},
    getStorageSync: function () { return ''; },
    setStorageSync: function () {},
    onThemeChange: function () {},
    offThemeChange: function () {},
    getSystemInfoSync: function () { return {}; }
  };

  delete require.cache[pagePath];
  require(pagePath);

  if (originalApiModule) require.cache[apiPath] = originalApiModule;
  else delete require.cache[apiPath];
  global.Page = originalPage;
  global.wx = originalWx;
  global.getApp = originalGetApp;

  const page = Object.assign({}, definition, {
    data: Object.assign({}, definition.data),
    setData: function (changes) { Object.assign(page.data, changes); }
  });
  page.__calls = calls;
  page.__toasts = toasts;
  page.__installGlobals = function () {
    global.wx = {
      showToast: function (options) { toasts.push(options); },
      redirectTo: function () {}
    };
    global.getApp = function () { return app; };
  };
  return page;
}

test('saved nickname is restored from authenticated user data', async () => {
  const bootstrap = { user: { idolId: 'idol-1', nickname: '小博' }, hasIdol: true };
  const page = loadPage(function (action) {
    if (action === 'getHome') {
      return Promise.resolve({
        user: { idolId: 'idol-1', nickname: '小博' },
        idol: { _id: 'idol-1', name: '王一博' },
        stats: { sourceCount: 1 }
      });
    }
    return Promise.resolve({ sources: [] });
  }, bootstrap);
  page.__installGlobals();

  await page.loadData();

  assert.equal(page.data.nickname, '小博');
  assert.equal(page.data.nicknameDraft, '小博');
});

test('saving nickname trims it, updates the page and invalidates bootstrap', async () => {
  const page = loadPage(function () {
    return Promise.resolve({ user: { nickname: '小<博>&' } });
  });
  page.data.nicknameDraft = '  小<博>&  ';
  page.__installGlobals();

  await page.saveNickname();

  assert.deepEqual(page.__calls, [
    ['updateNickname', { nickname: '小<博>&' }],
    ['invalidateBootstrap']
  ]);
  assert.equal(page.data.nickname, '小<博>&');
  assert.equal(page.data.nicknameDraft, '小<博>&');
  assert.equal(page.data.savingNickname, false);
  assert.deepEqual(page.__toasts.map((toast) => toast.title), ['昵称已保存']);
});

test('blank nickname is rejected before sending a request', () => {
  const page = loadPage(function () {
    throw new Error('不应发请求');
  });
  page.data.nicknameDraft = '   ';
  page.__installGlobals();

  const result = page.saveNickname();

  assert.equal(result, undefined);
  assert.deepEqual(page.__calls, []);
  assert.deepEqual(page.__toasts.map((toast) => toast.title), ['昵称不能为空']);
});
