var config = require('./config/env');
var api = require('./utils/api');

function login() {
  return new Promise(function (resolve, reject) {
    wx.login({ success: resolve, fail: reject });
  });
}

App({
  globalData: {
    bootstrap: null,
    bootstrapPromise: null
  },

  onLaunch: function () {
    if (!wx.cloud) {
      console.error('当前微信基础库不支持云开发');
      return;
    }

    var cloudOptions = { traceUser: true };
    if (config.cloudEnvId) {
      cloudOptions.env = config.cloudEnvId;
    } else if (wx.cloud.DYNAMIC_CURRENT_ENV) {
      cloudOptions.env = wx.cloud.DYNAMIC_CURRENT_ENV;
    }
    wx.cloud.init(cloudOptions);

    this.ensureBootstrap().catch(function (error) {
      console.error('Silent bootstrap failed:', error);
    });
  },

  ensureBootstrap: function (options) {
    options = options || {};
    if (options.force) {
      this.globalData.bootstrap = null;
      this.globalData.bootstrapPromise = null;
    }
    if (this.globalData.bootstrap) {
      return Promise.resolve(this.globalData.bootstrap);
    }
    if (this.globalData.bootstrapPromise) {
      return this.globalData.bootstrapPromise;
    }

    var app = this;
    this.globalData.bootstrapPromise = login().then(function () {
      return api.callUser('bootstrap');
    }).then(function (data) {
      app.globalData.bootstrap = data || {};
      return app.globalData.bootstrap;
    }).catch(function (error) {
      app.globalData.bootstrapPromise = null;
      throw error;
    });
    return this.globalData.bootstrapPromise;
  },

  invalidateBootstrap: function () {
    this.globalData.bootstrap = null;
    this.globalData.bootstrapPromise = null;
  }
});
