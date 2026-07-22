var config = require('../config/env');
var api = require('./api');

function requestSubscribeMessage(templateId) {
  return new Promise(function (resolve, reject) {
    wx.requestSubscribeMessage({
      tmplIds: [templateId],
      success: resolve,
      fail: reject
    });
  });
}

function requestSubscription() {
  var templateId = config.subscribeTemplateId;
  if (!templateId) {
    wx.showModal({
      title: '订阅模板未配置',
      content: '请先在 miniprogram/config/env.js 填写审核通过的订阅消息模板 ID。',
      showCancel: false,
      confirmColor: '#c4526e'
    });
    return Promise.resolve(false);
  }

  return requestSubscribeMessage(templateId).then(function (result) {
    if (result[templateId] !== 'accept') {
      wx.showToast({ title: '未开启提醒', icon: 'none' });
      return false;
    }
    return api.callUser('recordSubscription', { accepted: true }).then(function () {
      wx.showToast({ title: '提醒已开启', icon: 'success' });
      return true;
    });
  });
}

module.exports = {
  requestSubscription: requestSubscription
};
