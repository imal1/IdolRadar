function normalizeBackendError(error) {
  if (typeof error === 'string') {
    return { message: error, code: 'BACKEND_ERROR' };
  }

  error = error || {};
  return {
    message: error.message || '服务暂时不可用，请稍后重试',
    code: error.code || 'BACKEND_ERROR'
  };
}

function callUser(action, payload) {
  return wx.cloud.callFunction({
    name: 'user',
    data: Object.assign({ action: action }, payload || {})
  }).then(function (response) {
    var result = response && response.result;
    if (!result || result.ok !== true) {
      var detail = normalizeBackendError(result && result.error);
      var error = new Error(detail.message);
      error.code = detail.code;
      throw error;
    }
    return result.data;
  });
}

module.exports = {
  callUser: callUser
};
