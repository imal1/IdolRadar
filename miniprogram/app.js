var api = require('./utils/api');

// 全局只缓存“登录后初始化数据”；页面业务数据仍在 onShow/loadData 时刷新。
App({
  globalData: {
    bootstrap: null,
    bootstrapPromise: null,
    bootstrapGeneration: 0
  },

  onLaunch: function () {
    this.ensureBootstrap().catch(function (error) {
      console.error('Silent bootstrap failed:', error);
    });
  },

  ensureBootstrap: function (options) {
    options = options || {};
    if (options.force) {
      this.globalData.bootstrapGeneration += 1;
      this.globalData.bootstrap = null;
      this.globalData.bootstrapPromise = null;
    }
    if (this.globalData.bootstrap) {
      return Promise.resolve(this.globalData.bootstrap);
    }
    if (this.globalData.bootstrapPromise) {
      // 多个页面同时启动时复用同一请求，避免重复 wx.login 和初始化接口。
      return this.globalData.bootstrapPromise;
    }

    var app = this;
    var generation = this.globalData.bootstrapGeneration;
    var bootstrapPromise = api.callUser('bootstrap').then(function (data) {
      var bootstrap = data || {};
      // force 刷新会递增 generation；旧请求晚返回时不得覆盖新状态。
      if (
        app.globalData.bootstrapGeneration === generation &&
        app.globalData.bootstrapPromise === bootstrapPromise
      ) {
        app.globalData.bootstrap = bootstrap;
        app.globalData.bootstrapPromise = null;
      }
      return bootstrap;
    }, function (error) {
      if (
        app.globalData.bootstrapGeneration === generation &&
        app.globalData.bootstrapPromise === bootstrapPromise
      ) {
        app.globalData.bootstrapPromise = null;
      }
      throw error;
    });
    this.globalData.bootstrapPromise = bootstrapPromise;
    return bootstrapPromise;
  },

  invalidateBootstrap: function () {
    // 更换守护对象后废弃缓存，也让仍在飞行中的旧请求失去写入资格。
    this.globalData.bootstrapGeneration += 1;
    this.globalData.bootstrap = null;
    this.globalData.bootstrapPromise = null;
  }
});
