var config = require('../config/env');

var TOKEN_STORAGE_KEY = 'idolRadarAccessToken';
var ACCOUNT_DELETED_STORAGE_KEY = 'idolRadarAccountDeleted';
var ACCOUNT_DELETION_PENDING_STORAGE_KEY = 'idolRadarAccountDeletionPending';
var loginPromise = null;
var authenticationSuspended = false;

// 小程序动作与 REST 契约集中维护，页面不得自行拼 URL 或认证头。
var ACTIONS = {
  createAccountDeletionRequest: { method: 'POST', path: '/v1/me/account-deletion-requests' },
  deleteAccount: { method: 'DELETE', path: '/v1/me' },
  bootstrap: { method: 'GET', path: '/v1/me/bootstrap' },
  getHome: { method: 'GET', path: '/v1/home' },
  getFeed: { method: 'GET', path: '/v1/feed', query: ['cursor'] },
  listIdols: { method: 'GET', path: '/v1/idols' },
  updateNickname: { method: 'PUT', path: '/v1/me/profile' },
  setIdol: { method: 'PUT', path: '/v1/me/idol' },
  recordSubscription: { method: 'POST', path: '/v1/me/subscriptions' },
  submitIdolRequest: { method: 'POST', path: '/v1/idol-requests' },
  listMyIdolRequests: { method: 'GET', path: '/v1/me/idol-requests' },
  listMySources: { method: 'GET', path: '/v1/me/sources' },
  // :sourceId 由 params 声明并从 payload 取值；页面仍然不接触 URL 拼接。
  muteSource: { method: 'PUT', path: '/v1/me/sources/:sourceId/mute', params: ['sourceId'] },
  unmuteSource: { method: 'DELETE', path: '/v1/me/sources/:sourceId/mute', params: ['sourceId'] },
  reportNotificationOpen: {
    method: 'POST',
    path: '/v1/notification-deliveries/:postId/open',
    params: ['postId']
  }
};

function createError(detail, fallbackCode) {
  detail = normalizeBackendError(detail, fallbackCode);
  var error = new Error(detail.message);
  error.code = detail.code;
  return error;
}

function normalizeBackendError(error, fallbackCode) {
  if (typeof error === 'string') {
    return { message: error, code: fallbackCode || 'BACKEND_ERROR' };
  }

  error = error || {};
  return {
    message: error.message || '服务暂时不可用，请稍后重试',
    code: error.code || fallbackCode || 'BACKEND_ERROR'
  };
}

function getToken() {
  return String(wx.getStorageSync(TOKEN_STORAGE_KEY) || '');
}

function saveToken(token) {
  wx.setStorageSync(TOKEN_STORAGE_KEY, token);
}

function clearToken() {
  wx.removeStorageSync(TOKEN_STORAGE_KEY);
}

function isAccountDeleted() {
  return wx.getStorageSync(ACCOUNT_DELETED_STORAGE_KEY) === true;
}

function pendingDeletionRequestId() {
  var value = wx.getStorageSync(ACCOUNT_DELETION_PENDING_STORAGE_KEY);
  return typeof value === 'string' ? value : '';
}

function isAccountDeletionPending() {
  return pendingDeletionRequestId() !== '';
}

function isAuthenticationSuspended() {
  return authenticationSuspended || isAccountDeleted() || isAccountDeletionPending();
}

function suspendedAuthenticationError() {
  return isAccountDeleted()
    ? createError({
      message: '账号已注销，请明确选择重新开始',
      code: 'ACCOUNT_DELETED'
    })
    : createError({
      message: '注销结果待确认，请返回注销完成页重试',
      code: 'ACCOUNT_DELETION_PENDING'
    });
}

function login() {
  return new Promise(function (resolve, reject) {
    wx.login({
      success: function (result) {
        if (!result.code) {
          reject(createError({ message: '微信登录未返回 code', code: 'WECHAT_LOGIN_FAILED' }));
          return;
        }
        resolve(result.code);
      },
      fail: function (error) {
        reject(createError({
          message: error && error.errMsg ? error.errMsg : '微信登录失败，请稍后重试',
          code: 'WECHAT_LOGIN_FAILED'
        }));
      }
    });
  });
}

function apiBaseUrl() {
  return String(config.apiBaseUrl || '').replace(/\/+$/, '');
}

function unwrapResponse(body) {
  if (!body || body.ok !== true) {
    throw createError(body && body.error);
  }
  return body.data;
}

function request(options) {
  // 所有接口统一解包 { ok, data, error }，页面只处理业务数据与标准错误码。
  return new Promise(function (resolve, reject) {
    wx.request({
      url: apiBaseUrl() + options.path,
      method: options.method,
      data: options.data,
      header: options.header || { 'content-type': 'application/json' },
      timeout: 15000,
      success: function (response) {
        var statusCode = response.statusCode || 0;
        if (statusCode >= 200 && statusCode < 300) {
          try {
            resolve(unwrapResponse(response.data));
          } catch (error) {
            reject(error);
          }
          return;
        }

        var detail = response.data && (response.data.error || response.data);
        var error = createError(detail, 'HTTP_' + statusCode);
        error.statusCode = statusCode;
        reject(error);
      },
      fail: function (error) {
        reject(createError({
          message: error && error.errMsg ? error.errMsg : '网络连接失败，请稍后重试',
          code: 'NETWORK_ERROR'
        }));
      }
    });
  });
}

function authenticate(force) {
  if (isAuthenticationSuspended()) {
    return Promise.reject(suspendedAuthenticationError());
  }
  var token = getToken();
  if (!force && token) {
    return Promise.resolve(token);
  }
  if (loginPromise) {
    // wx.login 的 code 只能使用一次；并发请求必须共享同一次登录交换。
    return loginPromise;
  }

  if (force) {
    clearToken();
  }

  loginPromise = login().then(function (code) {
    return request({
      method: 'POST',
      path: '/v1/auth/wechat/login',
      data: { code: code }
    });
  }).then(function (data) {
    var token = data && data.token;
    if (!token) {
      throw createError({ message: '登录响应缺少 token', code: 'INVALID_LOGIN_RESPONSE' });
    }
    saveToken(token);
    return token;
  });

  loginPromise = loginPromise.then(function (token) {
    loginPromise = null;
    return token;
  }, function (error) {
    loginPromise = null;
    throw error;
  });
  return loginPromise;
}

function appendQuery(path, fields, payload) {
  var query = (fields || []).filter(function (field) {
    return payload[field] !== undefined && payload[field] !== null && payload[field] !== '';
  }).map(function (field) {
    return encodeURIComponent(field) + '=' + encodeURIComponent(payload[field]);
  });
  return query.length ? path + '?' + query.join('&') : path;
}

// 把 :name 占位符替换成 payload 里的值，并返回剩余字段作为请求体。
// 路径参数留在 body 里没有意义，后端开启了未知字段拒绝，多送反而是风险。
function applyPathParams(path, fields, payload) {
  var body = {};
  Object.keys(payload).forEach(function (key) {
    body[key] = payload[key];
  });
  (fields || []).forEach(function (field) {
    var value = body[field];
    if (value === undefined || value === null || value === '') {
      throw createError({ message: '缺少路径参数：' + field, code: 'MISSING_PATH_PARAM' });
    }
    path = path.replace(':' + field, encodeURIComponent(value));
    delete body[field];
  });
  return { path: path, body: body };
}

function performAction(action, payload, token) {
  var definition = ACTIONS[action];
  var resolved = applyPathParams(definition.path, definition.params, payload);
  var path = appendQuery(resolved.path, definition.query, payload);
  return request({
    method: definition.method,
    path: path,
    data: definition.method === 'GET' ? undefined : resolved.body,
    header: {
      'content-type': 'application/json',
      Authorization: 'Bearer ' + token
    }
  });
}

function accountDeletionUncertainError() {
  return createError({
    message: '注销结果暂未确认，请重试确认',
    code: 'ACCOUNT_DELETION_UNCERTAIN'
  });
}

function markAccountDeleted(data) {
  // 持久标记让冷启动也保持注销态；只有用户主动重新开始才清除。
  wx.setStorageSync(ACCOUNT_DELETED_STORAGE_KEY, true);
  wx.removeStorageSync(ACCOUNT_DELETION_PENDING_STORAGE_KEY);
  clearToken();
  return data || { deleted: true };
}

function isUncertainDeleteError(error) {
  return error.code === 'NETWORK_ERROR'
    || error.statusCode === 408
    || error.statusCode >= 500;
}

function getAccountDeletionStatus(requestId) {
  return request({
    method: 'GET',
    path: '/v1/account-deletions/' + encodeURIComponent(requestId)
  });
}

function resolvePendingDeletion(requestId) {
  return getAccountDeletionStatus(requestId).then(function (status) {
    if (status && status.completed === true) {
      return markAccountDeleted({ deleted: true });
    }
    // false 也可能表示原 DELETE 尚在途。只走注销专用微信身份验证，服务端不会建档或签普通会话。
    return login().then(function (code) {
      return request({
        method: 'POST',
        path: '/v1/account-deletions/' + encodeURIComponent(requestId) + '/retry',
        data: { code: code }
      });
    }).then(markAccountDeleted, function () {
      throw accountDeletionUncertainError();
    });
  }, function () {
    throw accountDeletionUncertainError();
  });
}

function deletionRequestId(data) {
  var requestId = String(data && data.requestId || '');
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(requestId)) {
    throw createError({ message: '注销请求标识无效', code: 'INVALID_DELETION_RESPONSE' });
  }
  return requestId;
}

function callUser(action, payload) {
  var definition = ACTIONS[action];
  if (!definition) {
    return Promise.reject(createError({
      message: '不支持的用户操作：' + action,
      code: 'UNKNOWN_ACTION'
    }));
  }

  payload = payload || {};
  return authenticate(false).then(function (token) {
    return performAction(action, payload, token).catch(function (error) {
      if (error.statusCode !== 401) {
        throw error;
      }
      if (isAuthenticationSuspended()) {
        throw suspendedAuthenticationError();
      }

      var currentToken = getToken();
      // 401 最多重放一次。若别的请求已刷新 token，直接复用，避免登录风暴。
      var refresh = currentToken && currentToken !== token
        ? Promise.resolve(currentToken)
        : authenticate(true);
      return refresh.then(function (freshToken) {
        return performAction(action, payload, freshToken);
      });
    });
  });
}

function deleteAccount() {
  var pendingLogin = loginPromise;
  var token = getToken();
  var wasPending = isAccountDeletionPending();
  var requestId = pendingDeletionRequestId();
  var deleteStarted = false;
  // 发出 DELETE 前先暂停所有新认证；已有登录完成后仅把 token 交给本次删除。
  authenticationSuspended = true;
  var tokenReady = pendingLogin || (token
    ? Promise.resolve(token)
    : Promise.reject(createError({ message: '登录已失效，请重新进入小程序', code: 'UNAUTHORIZED' })));

  if (wasPending && !pendingLogin && !token) {
    return resolvePendingDeletion(requestId);
  }

  // 注销不走 callUser 的 401 自动重放，避免服务端删除后又静默建档。
  return tokenReady.then(function (authenticatedToken) {
    var requestIdReady = requestId
      ? Promise.resolve(requestId)
      : performAction('createAccountDeletionRequest', {}, authenticatedToken).then(deletionRequestId);
    return requestIdReady.then(function (resolvedRequestId) {
      requestId = resolvedRequestId;
      // 服务端安全生成 ID 后、DELETE 发出前落盘；进程中止也不会静默重新登录。
      wx.setStorageSync(ACCOUNT_DELETION_PENDING_STORAGE_KEY, requestId);
      deleteStarted = true;
      return performAction('deleteAccount', { requestId: requestId }, authenticatedToken);
    }).then(function (data) {
      return markAccountDeleted(data);
    }, function (error) {
      if (!deleteStarted) {
        authenticationSuspended = false;
        throw error;
      }
      if (wasPending && error.statusCode === 401) {
        // 401 只说明会话失效，不能证明删除成功；先查匿名回执再决定是否重新认证。
        return resolvePendingDeletion(requestId);
      }
      if (wasPending || isUncertainDeleteError(error)) {
        // 超时、断网和网关 5xx 都不能证明事务未提交，继续阻止冷启动建档。
        throw accountDeletionUncertainError();
      }
      wx.removeStorageSync(ACCOUNT_DELETION_PENDING_STORAGE_KEY);
      authenticationSuspended = false;
      throw error;
    });
  }, function (error) {
    // 尚未取得 token，DELETE 没有发出，可以安全恢复原认证状态。
    if (!wasPending) {
      wx.removeStorageSync(ACCOUNT_DELETION_PENDING_STORAGE_KEY);
    }
    authenticationSuspended = false;
    throw error;
  });
}

function resumeAfterAccountDeletion() {
  // 只有用户在注销完成页明确选择重新开始，才允许同一微信身份重新建档。
  authenticationSuspended = false;
  wx.removeStorageSync(ACCOUNT_DELETED_STORAGE_KEY);
  wx.removeStorageSync(ACCOUNT_DELETION_PENDING_STORAGE_KEY);
}

module.exports = {
  authenticate: authenticate,
  callUser: callUser,
  deleteAccount: deleteAccount,
  isAccountDeleted: isAccountDeleted,
  isAccountDeletionPending: isAccountDeletionPending,
  resumeAfterAccountDeletion: resumeAfterAccountDeletion
};
