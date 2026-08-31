'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const apiPath = require.resolve('../miniprogram/utils/api');

function loadApi(options) {
  options = options || {};
  var storage = {
    idolRadarAccessToken: options.token || '',
    idolRadarAccountDeleted: options.accountDeleted === true,
    idolRadarAccountDeletionPending: options.accountDeletionRequestId || ''
  };
  var calls = {
    login: 0,
    requests: []
  };

  global.wx = {
    getStorageSync: function (key) {
      return storage[key];
    },
    setStorageSync: function (key, value) {
      storage[key] = value;
    },
    removeStorageSync: function (key) {
      delete storage[key];
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
    getStoredToken: function () { return storage.idolRadarAccessToken || ''; },
    getStoredDeletionRequestId: function () {
      return String(storage.idolRadarAccountDeletionPending || '');
    },
    reloadApi: function () {
      delete require.cache[apiPath];
      return require(apiPath);
    }
  };
}

function respond(request, statusCode, body) {
  request.success({ statusCode: statusCode, data: body });
}

const deletionRequestId = '11111111-1111-4111-8111-111111111111';

function respondToDeletionRequestId(request) {
  if (!/\/v1\/me\/account-deletion-requests$/.test(request.url)) {
    return false;
  }
  respond(request, 200, { ok: true, data: { requestId: deletionRequestId } });
  return true;
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

test('nickname update uses the authenticated profile endpoint', async () => {
  const loaded = loadApi({
    token: 'existing-token',
    onRequest(request) {
      respond(request, 200, { ok: true, data: { user: { nickname: '小<博>&' } } });
    }
  });

  await loaded.api.callUser('updateNickname', { nickname: '小<博>&' });

  assert.equal(loaded.calls.requests.length, 1);
  assert.match(loaded.calls.requests[0].url, /\/v1\/me\/profile$/);
  assert.equal(loaded.calls.requests[0].method, 'PUT');
  assert.deepEqual(loaded.calls.requests[0].data, { nickname: '小<博>&' });
});

test('account deletion uses DELETE /v1/me and clears the token only after success', async () => {
  const loaded = loadApi({
    token: 'existing-token',
    onRequest(request) {
      if (respondToDeletionRequestId(request)) {
        return;
      }
      respond(request, 200, { ok: true, data: { deleted: true } });
    }
  });

  await loaded.api.deleteAccount();

  assert.equal(loaded.calls.requests.length, 2);
  assert.match(loaded.calls.requests[0].url, /\/v1\/me\/account-deletion-requests$/);
  assert.equal(loaded.calls.requests[0].method, 'POST');
  assert.match(loaded.calls.requests[1].url, /\/v1\/me$/);
  assert.equal(loaded.calls.requests[1].method, 'DELETE');
  assert.equal(loaded.calls.requests[1].data.requestId, deletionRequestId);
  assert.equal(loaded.getStoredToken(), '');
  assert.equal(loaded.getStoredDeletionRequestId(), '');
});

test('successful account deletion suspends silent login until the user explicitly restarts', async () => {
  const loaded = loadApi({
    token: 'existing-token',
    onRequest(request) {
      if (/\/v1\/auth\/wechat\/login$/.test(request.url)) {
        respond(request, 200, { ok: true, data: { token: 'fresh-token' } });
        return;
      }
      if (respondToDeletionRequestId(request)) {
        return;
      }
      respond(request, 200, { ok: true, data: { deleted: true } });
    }
  });

  await loaded.api.deleteAccount();
  const reloadedApi = loaded.reloadApi();
  await assert.rejects(
    () => reloadedApi.authenticate(false),
    (error) => error.code === 'ACCOUNT_DELETED'
  );
  assert.equal(loaded.calls.login, 0);

  reloadedApi.resumeAfterAccountDeletion();
  assert.equal(await reloadedApi.authenticate(false), 'fresh-token');
  assert.equal(loaded.calls.login, 1);
});

test('account deletion suspends new actions before waiting for an existing login', async () => {
  let loginRequest;
  const loaded = loadApi({
    onRequest(request) {
      if (/\/v1\/auth\/wechat\/login$/.test(request.url)) {
        loginRequest = request;
        return;
      }
      if (respondToDeletionRequestId(request)) {
        return;
      }
      respond(request, 200, { ok: true, data: { deleted: true } });
    }
  });

  const pendingLogin = loaded.api.authenticate(false);
  await new Promise((resolve) => setImmediate(resolve));
  const deletion = loaded.api.deleteAccount();
  const concurrentAction = loaded.api.callUser('getHome');

  await assert.rejects(concurrentAction, (error) => error.code === 'ACCOUNT_DELETION_PENDING');
  respond(loginRequest, 200, { ok: true, data: { token: 'fresh-token' } });
  await pendingLogin;
  await deletion;

  assert.equal(loaded.calls.login, 1);
  assert.equal(loaded.calls.requests.filter((request) => /\/v1\/home$/.test(request.url)).length, 0);
  assert.equal(loaded.calls.requests.filter((request) => request.method === 'DELETE').length, 1);
});

test('a rejected account deletion never silently logs in or replays DELETE', async () => {
  const loaded = loadApi({
    token: 'expired-token',
    onRequest(request) {
      if (respondToDeletionRequestId(request)) {
        return;
      }
      respond(request, 401, {
        ok: false,
        error: { code: 'UNAUTHORIZED', message: '登录已过期' }
      });
    }
  });

  await assert.rejects(() => loaded.api.deleteAccount(), (error) => error.statusCode === 401);

  assert.equal(loaded.calls.login, 0);
  assert.equal(loaded.calls.requests.length, 2);
  assert.equal(loaded.calls.requests[1].method, 'DELETE');
});

test('a DELETE is marked pending before the request can finish', async () => {
  var inFlightRequest;
  const loaded = loadApi({
    token: 'existing-token',
    onRequest(request) {
      if (respondToDeletionRequestId(request)) {
        return;
      }
      inFlightRequest = request;
    }
  });

  const deletion = loaded.api.deleteAccount();
  await new Promise((resolve) => setImmediate(resolve));

  const reloadedApi = loaded.reloadApi();
  assert.match(loaded.getStoredDeletionRequestId(), /^[0-9a-f-]{36}$/);
  await assert.rejects(
    () => reloadedApi.authenticate(false),
    (error) => error.code === 'ACCOUNT_DELETION_PENDING'
  );
  assert.equal(loaded.calls.login, 0);

  inFlightRequest.fail({ errMsg: 'request:fail timeout' });
  await assert.rejects(deletion, (error) => error.code === 'ACCOUNT_DELETION_UNCERTAIN');
});

test('a completed receipt resolves a lost DELETE response without logging in', async () => {
  var deletionAttempts = 0;
  const loaded = loadApi({
    token: 'existing-token',
    onRequest(request) {
      if (respondToDeletionRequestId(request)) {
        return;
      }
      if (request.method === 'DELETE') {
        deletionAttempts += 1;
        if (deletionAttempts === 1) {
          request.fail({ errMsg: 'request:fail timeout' });
          return;
        }
        respond(request, 401, {
          ok: false,
          error: { code: 'UNAUTHORIZED', message: '登录已失效' }
        });
        return;
      }
      assert.match(request.url, /\/v1\/account-deletions\/[0-9a-f-]{36}$/);
      respond(request, 200, { ok: true, data: { completed: true } });
    }
  });

  await assert.rejects(() => loaded.api.deleteAccount(),
    (error) => error.code === 'ACCOUNT_DELETION_UNCERTAIN');
  const requestId = loaded.getStoredDeletionRequestId();
  const reloadedApi = loaded.reloadApi();

  assert.deepEqual(await reloadedApi.deleteAccount(), { deleted: true });
  assert.equal(loaded.calls.requests[2].data.requestId, requestId);
  assert.equal(reloadedApi.isAccountDeletionPending(), false);
  assert.equal(reloadedApi.isAccountDeleted(), true);
  assert.equal(loaded.getStoredToken(), '');
  assert.equal(loaded.calls.login, 0);
});

test('an unfinished receipt uses deletion-only WeChat verification without normal login', async () => {
  const loaded = loadApi({
    token: 'expired-token',
    accountDeletionRequestId: deletionRequestId,
    onRequest(request) {
      if (/\/v1\/account-deletions\//.test(request.url)) {
        if (/\/retry$/.test(request.url)) {
          respond(request, 200, { ok: true, data: { deleted: true } });
          return;
        }
        respond(request, 200, { ok: true, data: { completed: false } });
        return;
      }
      respond(request, 401, {
        ok: false,
        error: { code: 'UNAUTHORIZED', message: '旧会话已失效' }
      });
    }
  });

  assert.deepEqual(await loaded.api.deleteAccount(), { deleted: true });
  assert.equal(loaded.calls.login, 1);
  assert.equal(loaded.calls.requests.filter((request) =>
    /\/v1\/auth\/wechat\/login$/.test(request.url)).length, 0);
  const recovery = loaded.calls.requests.find((request) => /\/retry$/.test(request.url));
  assert.equal(recovery.method, 'POST');
  assert.deepEqual(recovery.data, { code: 'wx-login-code' });
  assert.equal(loaded.api.isAccountDeletionPending(), false);
  assert.equal(loaded.api.isAccountDeleted(), true);
});

test('a failed deletion-only recovery remains pending and never calls normal login', async () => {
  const loaded = loadApi({
    token: 'expired-token',
    accountDeletionRequestId: deletionRequestId,
    onRequest(request) {
      if (/\/retry$/.test(request.url)) {
        respond(request, 503, {
          ok: false,
          error: { code: 'SERVICE_UNAVAILABLE', message: '服务维护中' }
        });
        return;
      }
      if (/\/v1\/account-deletions\//.test(request.url)) {
        respond(request, 200, { ok: true, data: { completed: false } });
        return;
      }
      respond(request, 401, {
        ok: false,
        error: { code: 'UNAUTHORIZED', message: '旧会话已失效' }
      });
    }
  });

  await assert.rejects(
    () => loaded.api.deleteAccount(),
    (error) => error.code === 'ACCOUNT_DELETION_UNCERTAIN'
  );
  assert.equal(loaded.calls.requests.filter((request) =>
    /\/v1\/auth\/wechat\/login$/.test(request.url)).length, 0);
  assert.equal(loaded.api.isAccountDeletionPending(), true);
  assert.equal(loaded.api.isAccountDeleted(), false);
});

test('a known rejected account deletion restores the current account state', async () => {
  const loaded = loadApi({
    token: 'existing-token',
    onRequest(request) {
      if (respondToDeletionRequestId(request)) {
        return;
      }
      respond(request, 400, {
        ok: false,
        error: { code: 'INVALID_INPUT', message: '请求参数无效' }
      });
    }
  });

  await assert.rejects(() => loaded.api.deleteAccount(), (error) => error.statusCode === 400);

  assert.equal(loaded.getStoredToken(), 'existing-token');
  assert.equal(loaded.api.isAccountDeletionPending(), false);
});

test('a gateway 5xx keeps deletion pending because commit status is unknown', async () => {
  const loaded = loadApi({
    token: 'existing-token',
    onRequest(request) {
      if (respondToDeletionRequestId(request)) {
        return;
      }
      respond(request, 504, {
        ok: false,
        error: { code: 'HTTP_504', message: '网关超时' }
      });
    }
  });

  await assert.rejects(
    () => loaded.api.deleteAccount(),
    (error) => error.code === 'ACCOUNT_DELETION_UNCERTAIN'
  );

  assert.equal(loaded.getStoredToken(), 'existing-token');
  assert.equal(loaded.api.isAccountDeletionPending(), true);
});

test('source mute actions substitute the path parameter and keep it out of the body', async () => {
  const loaded = loadApi({
    token: 'existing-token',
    onRequest(request) {
      respond(request, 200, { ok: true, data: { sourceId: 'source-1', muted: true } });
    }
  });

  await loaded.api.callUser('muteSource', { sourceId: 'source-1' });
  await loaded.api.callUser('unmuteSource', { sourceId: 'source-1' });

  const [mute, unmute] = loaded.calls.requests;
  assert.match(mute.url, /\/v1\/me\/sources\/source-1\/mute$/);
  assert.equal(mute.method, 'PUT');
  assert.equal(unmute.method, 'DELETE');
  // 路径参数不能留在请求体里：后端开启了未知字段拒绝，多送就是一个 400。
  assert.deepEqual(mute.data, {});
  assert.deepEqual(unmute.data, {});
});

test('source ids are percent-encoded so they cannot escape the path', async () => {
  const loaded = loadApi({
    token: 'existing-token',
    onRequest(request) {
      respond(request, 200, { ok: true, data: {} });
    }
  });

  await loaded.api.callUser('muteSource', { sourceId: 'a/b?c' });

  assert.match(loaded.calls.requests[0].url, /\/v1\/me\/sources\/a%2Fb%3Fc\/mute$/);
});

test('a missing path parameter fails before any request is sent', async () => {
  const loaded = loadApi({
    token: 'existing-token',
    onRequest(request) {
      respond(request, 200, { ok: true, data: {} });
    }
  });

  await assert.rejects(
    () => loaded.api.callUser('muteSource', {}),
    (error) => error.code === 'MISSING_PATH_PARAM'
  );
  assert.equal(loaded.calls.requests.length, 0);
});
