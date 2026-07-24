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

function loadApp(callUser) {
  var originalApiModule = require.cache[apiPath];
  var originalApp = global.App;
  var definition;

  require.cache[apiPath] = {
    id: apiPath,
    filename: apiPath,
    loaded: true,
    exports: { callUser: callUser }
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
