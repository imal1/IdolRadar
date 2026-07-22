var api = require('../../utils/api');
var idolUtils = require('../../utils/idol');
var subscription = require('../../utils/subscription');
var time = require('../../utils/time');
var config = require('../../config/env');

function getUser(data) {
  return data && data.user && typeof data.user === 'object' ? data.user : (data || {});
}

function getRawIdol(data) {
  data = data || {};
  var user = getUser(data);
  return data.idol || data.currentIdol || user.idol || null;
}

function getIdolId(data) {
  data = data || {};
  var user = getUser(data);
  var idol = getRawIdol(data) || {};
  return String(idol._id || idol.id || data.idolId || user.idolId || '');
}

function safeNumber(value) {
  value = Number(value);
  return isNaN(value) ? 0 : value;
}

function guardDays(home, user) {
  var stats = home.stats || {};
  var direct = idolUtils.firstDefined(stats.guardDays, home.guardDays);
  if (direct !== undefined) {
    return Math.max(1, safeNumber(direct));
  }

  var startedAt = user.guardingSince || user.guardStartedAt || user.idolSelectedAt || user.idolUpdatedAt;
  var started = time.toDate(startedAt);
  if (!started) {
    return 1;
  }
  return Math.max(1, Math.floor((Date.now() - started.getTime()) / 86400000) + 1);
}

Page({
  data: {
    loading: true,
    subscribing: false,
    errorMessage: '',
    idol: null,
    guardDays: 1,
    sourceCount: 0,
    subscribeQuota: 0,
    version: config.version
  },

  onShow: function () {
    this.loadData();
  },

  loadData: function () {
    var page = this;
    var app = getApp();
    this.setData({ loading: true, errorMessage: '' });

    return app.ensureBootstrap({ force: true }).then(function (bootstrap) {
      if (!getIdolId(bootstrap)) {
        wx.redirectTo({ url: '/pages/picker/index?mode=first' });
        return null;
      }
      return api.callUser('getHome');
    }).then(function (home) {
      if (!home) {
        return;
      }
      if (!getRawIdol(home)) {
        app.invalidateBootstrap();
        wx.redirectTo({ url: '/pages/picker/index?mode=first' });
        return;
      }
      var bootstrap = app.globalData.bootstrap || {};
      var rawIdol = getRawIdol(home) || getRawIdol(bootstrap) || {};
      var idol = idolUtils.normalizeIdol(rawIdol, 0);
      idol.id = idol.id || getIdolId(home) || getIdolId(bootstrap);
      var user = getUser(home);
      var stats = home.stats || {};
      var sourceCount = safeNumber(idolUtils.firstDefined(
        stats.sourceCount,
        stats.sourcesCount,
        home.sourceCount,
        idol.sourceCount,
        0
      ));
      idol.sourceCount = sourceCount;
      page.setData({
        loading: false,
        idol: idol,
        guardDays: guardDays(home, user),
        sourceCount: sourceCount,
        subscribeQuota: safeNumber(idolUtils.firstDefined(user.subscribeQuota, home.subscribeQuota, 0))
      });
    }).catch(function (error) {
      page.setData({
        loading: false,
        errorMessage: error.message || '个人信息加载失败，请稍后重试'
      });
    });
  },

  retry: function () {
    this.loadData();
  },

  avatarError: function () {
    if (this.data.idol) {
      this.setData({ 'idol.avatarVisible': false });
    }
  },

  changeIdol: function () {
    wx.navigateTo({ url: '/pages/picker/index' });
  },

  enableSubscription: function () {
    var page = this;
    if (this.data.subscribing) {
      return;
    }
    this.setData({ subscribing: true });
    subscription.requestSubscription().then(function (accepted) {
      if (accepted) {
        page.setData({ subscribeQuota: page.data.subscribeQuota + 1 });
      }
    }).catch(function (error) {
      wx.showToast({ title: error.message || '开启失败，请重试', icon: 'none' });
    }).then(function () {
      page.setData({ subscribing: false });
    });
  },

  showAbout: function () {
    wx.showModal({
      title: '关于 IdolRadar',
      content: 'IdolRadar v' + this.data.version + '\n为你持续感应喜欢的那一位。',
      showCancel: false,
      confirmColor: '#c4526e'
    });
  },

  showSources: function () {
    wx.showModal({
      title: '动态来源说明',
      content: '动态来自管理员维护的公开 RSS 订阅源。仅展示标题、摘要与原文链接，版权归原平台及作者所有。',
      showCancel: false,
      confirmColor: '#c4526e'
    });
  }
});
