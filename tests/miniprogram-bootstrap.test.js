'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const apiPath = require.resolve('../miniprogram/utils/api');
const appPath = require.resolve('../miniprogram/app');

function deferred() {
  var resolve;
  var reject;
  var promise = new Promise(function (resolvePromise, rejectPromise) {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise: promise, resolve: resolve, reject: reject };
}

function loadApp(callUser, accountDeleted, accountDeletionPending) {
  var originalApiModule = require.cache[apiPath];
  var originalApp = global.App;
  var definition;

  require.cache[apiPath] = {
    id: apiPath,
    filename: apiPath,
    loaded: true,
    exports: {
      callUser: callUser,
      isAccountDeleted: function () { return accountDeleted === true; },
      isAccountDeletionPending: function () { return accountDeletionPending === true; }
    }
  };
  global.App = function (options) {
    definition = options;
  };

  delete require.cache[appPath];
  require(appPath);

  if (originalApiModule) {
    require.cache[apiPath] = originalApiModule;
  } else {
    delete require.cache[apiPath];
  }
  global.App = originalApp;
  return definition;
}

test('cold launch keeps a deleted account offline until the user explicitly restarts', () => {
  const originalWx = global.wx;
  const relaunches = [];
  var calls = 0;
  global.wx = {
    reLaunch: function (options) { relaunches.push(options); }
  };
  try {
    const app = loadApp(function () {
      calls += 1;
      return Promise.resolve({});
    }, true);

    app.onLaunch();

    assert.equal(calls, 0);
    assert.deepEqual(relaunches, [{ url: '/pages/me/index' }]);
  } finally {
    global.wx = originalWx;
  }
});

test('cold launch keeps an uncertain deletion offline until it is confirmed', () => {
  const originalWx = global.wx;
  const relaunches = [];
  var calls = 0;
  global.wx = {
    reLaunch: function (options) { relaunches.push(options); }
  };
  try {
    const app = loadApp(function () {
      calls += 1;
      return Promise.resolve({});
    }, false, true);

    app.onLaunch();

    assert.equal(calls, 0);
    assert.deepEqual(relaunches, [{ url: '/pages/me/index' }]);
  } finally {
    global.wx = originalWx;
  }
});

test('forced bootstrap prevents an older response from replacing the latest data', async () => {
  const older = deferred();
  const latest = deferred();
  const pending = [older, latest];
  const app = loadApp(function () {
    return pending.shift().promise;
  });

  const olderPromise = app.ensureBootstrap();
  const latestPromise = app.ensureBootstrap({ force: true });

  latest.resolve({ idolId: 'latest' });
  assert.deepEqual(await latestPromise, { idolId: 'latest' });
  assert.deepEqual(app.globalData.bootstrap, { idolId: 'latest' });
  assert.equal(app.globalData.bootstrapPromise, null);

  older.resolve({ idolId: 'older' });
  assert.deepEqual(await olderPromise, { idolId: 'older' });
  assert.deepEqual(app.globalData.bootstrap, { idolId: 'latest' });
  assert.equal(app.globalData.bootstrapPromise, null);
});

test('an older bootstrap rejection cannot clear the latest pending promise', async () => {
  const older = deferred();
  const latest = deferred();
  const pending = [older, latest];
  const app = loadApp(function () {
    return pending.shift().promise;
  });

  const olderPromise = app.ensureBootstrap();
  const latestPromise = app.ensureBootstrap({ force: true });
  const olderRejection = assert.rejects(olderPromise, /stale bootstrap failed/);

  older.reject(new Error('stale bootstrap failed'));
  await olderRejection;
  assert.strictEqual(app.globalData.bootstrapPromise, latestPromise);

  latest.resolve({ idolId: 'latest' });
  assert.deepEqual(await latestPromise, { idolId: 'latest' });
  assert.deepEqual(app.globalData.bootstrap, { idolId: 'latest' });
  assert.equal(app.globalData.bootstrapPromise, null);
});
