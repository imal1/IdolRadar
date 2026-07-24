var config = require('../config/env');

var TOKEN_STORAGE_KEY = 'idolRadarAccessToken';
var loginPromise = null;

// 小程序动作与 REST 契约集中维护，页面不得自行拼 URL 或认证头。
var ACTIONS = {
  bootstrap: { method: 'GET', path: '/v1/me/bootstrap' },
  getHome: { method: 'GET', path: '/v1/home' },
  getFeed: { method: 'GET', path: '/v1/feed', query: ['cursor'] },
  listIdols: { method: 'GET', path: '/v1/idols' },
  setIdol: { method: 'PUT', path: '/v1/me/idol' },
  recordSubscription: { method: 'POST', path: '/v1/me/subscriptions' }
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

function performAction(action, payload, token) {
  var definition = ACTIONS[action];
  var path = appendQuery(definition.path, definition.query, payload);
  return request({
    method: definition.method,
    path: path,
    data: definition.method === 'GET' ? undefined : payload,
    header: {
      'content-type': 'application/json',
      Authorization: 'Bearer ' + token
    }
  });
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

module.exports = {
  authenticate: authenticate,
  callUser: callUser
};
