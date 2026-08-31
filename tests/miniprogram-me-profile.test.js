'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const test = require('node:test');

const apiPath = require.resolve('../miniprogram/utils/api');
const pagePath = require.resolve('../miniprogram/pages/me/index');

function loadPage(callUser, bootstrap, modalResults) {
  const originalApiModule = require.cache[apiPath];
  const originalPage = global.Page;
  const originalWx = global.wx;
  const originalGetApp = global.getApp;
  const calls = [];
  const toasts = [];
  const modals = [];
  const relaunches = [];
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
      },
      deleteAccount() {
        calls.push(['deleteAccount']);
        return callUser('deleteAccount');
      },
      isAccountDeleted() {
        return false;
      },
      isAccountDeletionPending() {
        return false;
      },
      resumeAfterAccountDeletion() {
        calls.push(['resumeAfterAccountDeletion']);
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
  page.__modals = modals;
  page.__relaunches = relaunches;
  page.__installGlobals = function () {
    const results = (modalResults || []).slice();
    global.wx = {
      showToast: function (options) { toasts.push(options); },
      showModal: function (options) {
        modals.push(options);
        queueMicrotask(function () {
          options.success(results.shift() || { confirm: false, cancel: true });
        });
      },
      redirectTo: function () {},
      reLaunch: function (options) { relaunches.push(options); }
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

test('account deletion stops when the second irreversible confirmation is cancelled', async () => {
  const page = loadPage(function () {
    throw new Error('取消后不应发请求');
  }, null, [{ confirm: true }, { confirm: false, cancel: true }]);
  page.__installGlobals();

  await page.deleteAccount();

  assert.equal(page.__modals.length, 2);
  assert.match(page.__modals[0].content, /永久删除.*守护.*提醒额度.*来源设置.*推送记录/);
  assert.match(page.__modals[1].content, /无法恢复/);
  assert.deepEqual(page.__calls, []);
  assert.deepEqual(page.__relaunches, []);
});

test('account deletion stays on a completion screen until the user explicitly restarts', async () => {
  const page = loadPage(function (action) {
    assert.equal(action, 'deleteAccount');
    return Promise.resolve({ deleted: true });
  }, null, [{ confirm: true }, { confirm: true }]);
  page.__installGlobals();

  await page.deleteAccount();

  assert.deepEqual(page.__calls, [
    ['deleteAccount'],
    ['invalidateBootstrap']
  ]);
  assert.deepEqual(page.__relaunches, []);
  assert.equal(page.data.accountDeleted, true);
  assert.equal(page.data.deletingAccount, false);

  page.restartAfterAccountDeletion();

  assert.deepEqual(page.__calls, [
    ['deleteAccount'],
    ['invalidateBootstrap'],
    ['resumeAfterAccountDeletion']
  ]);
  assert.deepEqual(page.__relaunches, [{ url: '/pages/picker/index?mode=first' }]);
});

test('failed account deletion stays on the page and can be retried', async () => {
  const page = loadPage(function () {
    return Promise.reject(new Error('删除失败'));
  }, null, [{ confirm: true }, { confirm: true }]);
  page.__installGlobals();

  await page.deleteAccount();

  assert.deepEqual(page.__calls, [['deleteAccount']]);
  assert.deepEqual(page.__relaunches, []);
  assert.deepEqual(page.__toasts.map((toast) => toast.title), ['删除失败']);
  assert.equal(page.data.deletingAccount, false);
});

test('the me page exposes the account deletion entry', () => {
  const wxml = fs.readFileSync(require.resolve('../miniprogram/pages/me/index.wxml'), 'utf8');

  assert.match(wxml, /bindtap="deleteAccount"/);
  assert.match(wxml, /bindtap="restartAfterAccountDeletion"/);
  assert.match(wxml, /bindtap="retryAccountDeletion"/);
  assert.match(wxml, /注销结果待确认/);
  assert.match(wxml, /账号已注销/);
  assert.match(wxml, /注销账号/);
  assert.match(wxml, /永久删除/);
});
