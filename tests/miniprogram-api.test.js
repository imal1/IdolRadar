'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const apiPath = require.resolve('../miniprogram/utils/api');

function loadApi(options) {
  options = options || {};
  var storedToken = options.token || '';
  var calls = {
    login: 0,
    requests: []
  };

  global.wx = {
    getStorageSync: function () {
      return storedToken;
    },
    setStorageSync: function (key, value) {
      storedToken = value;
    },
    removeStorageSync: function () {
      storedToken = '';
    },
    login: function (request) {
      calls.login += 1;
      queueMicrotask(function () {
        request.success({ code: 'wx-login-code' });
      });
    },
    request: function (request) {
      calls.requests.push(request);
      queueMicrotask(function () {
        options.onRequest(request);
      });
    }
  };

  delete require.cache[apiPath];
  return {
    api: require(apiPath),
    calls: calls,
    getStoredToken: function () { return storedToken; }
  };
}

function respond(request, statusCode, body) {
  request.success({ statusCode: statusCode, data: body });
}

test('authenticate shares one wx.login and applies the 15s request timeout', async () => {
  const context = loadApi({
    onRequest: function (request) {
      assert.match(request.url, /\/v1\/auth\/wechat\/login$/);
      respond(request, 200, { ok: true, data: { token: 'fresh-token' } });
    }
  });

  const first = context.api.authenticate(false);
  const second = context.api.authenticate(false);

  assert.strictEqual(first, second);
  assert.deepEqual(await Promise.all([first, second]), ['fresh-token', 'fresh-token']);
  assert.equal(context.calls.login, 1);
  assert.equal(context.calls.requests.length, 1);
  assert.equal(context.calls.requests[0].timeout, 15000);
  assert.equal(context.getStoredToken(), 'fresh-token');
});

test('concurrent stale-token 401 responses trigger one login and retry once', async () => {
  const context = loadApi({
    token: 'stale-token',
    onRequest: function (request) {
      if (/\/v1\/auth\/wechat\/login$/.test(request.url)) {
        respond(request, 200, { ok: true, data: { token: 'fresh-token' } });
        return;
      }

      if (request.header.Authorization === 'Bearer stale-token') {
        respond(request, 401, {
          ok: false,
          error: { code: 'UNAUTHORIZED', message: '登录已过期' }
        });
        return;
      }

      respond(request, 200, { ok: true, data: { path: request.url } });
    }
  });

  const results = await Promise.all([
    context.api.callUser('bootstrap'),
    context.api.callUser('getHome')
  ]);

  assert.equal(context.calls.login, 1);
  assert.equal(context.calls.requests.filter(function (request) {
    return /\/v1\/auth\/wechat\/login$/.test(request.url);
  }).length, 1);
  assert.equal(context.calls.requests.filter(function (request) {
    return request.header.Authorization === 'Bearer stale-token';
  }).length, 2);
  assert.equal(context.calls.requests.filter(function (request) {
    return request.header.Authorization === 'Bearer fresh-token';
  }).length, 2);
  assert.equal(results.length, 2);
  assert.ok(context.calls.requests.every(function (request) {
    return request.timeout === 15000;
  }));
});

test('a 401 from the retried action does not start another login loop', async () => {
  const context = loadApi({
    token: 'stale-token',
    onRequest: function (request) {
      if (/\/v1\/auth\/wechat\/login$/.test(request.url)) {
        respond(request, 200, { ok: true, data: { token: 'fresh-token' } });
        return;
      }
      respond(request, 401, {
        ok: false,
        error: { code: 'UNAUTHORIZED', message: '登录已过期' }
      });
    }
  });

  await assert.rejects(
    context.api.callUser('getHome'),
    function (error) {
      return error.statusCode === 401 && error.code === 'UNAUTHORIZED';
    }
  );

  assert.equal(context.calls.login, 1);
  assert.equal(context.calls.requests.length, 3);
});

test('non-401 action errors do not trigger reauthentication', async () => {
  const context = loadApi({
    token: 'valid-token',
    onRequest: function (request) {
      respond(request, 503, {
        ok: false,
        error: { code: 'SERVICE_UNAVAILABLE', message: '服务维护中' }
      });
    }
  });

  await assert.rejects(
    context.api.callUser('getHome'),
    function (error) {
      return error.statusCode === 503 && error.code === 'SERVICE_UNAVAILABLE';
    }
  );

  assert.equal(context.calls.login, 0);
  assert.equal(context.calls.requests.length, 1);
});
