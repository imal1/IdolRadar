var api = require('../../utils/api');
var idolUtils = require('../../utils/idol');
var subscription = require('../../utils/subscription');
var theme = require('../../utils/theme');
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
  // 选择当天计作第 1 天；异常未来时间也不向用户展示 0 或负数。
  return Math.max(1, Math.floor((Date.now() - started.getTime()) / 86400000) + 1);
}

function normalizeSources(list) {
  if (!Array.isArray(list)) {
    return [];
  }
  return list.filter(function (source) {
    return source && typeof source === 'object' && !Array.isArray(source);
  }).map(function (source) {
    return {
      id: String(source._id || source.id || ''),
      // 展示名区分同一渠道下的不同账号，例如后援会与本人的微博。
      displayName: String(source.displayName || source.channel || '动态来源'),
      channel: String(source.channel || ''),
      muted: source.muted === true,
      pending: false
    };
  }).filter(function (source) {
    return source.id !== '';
  });
}

function isAllMuted(sources) {
  return sources.length > 0 && sources.every(function (source) {
    return source.muted;
  });
}

function findSourceIndex(sources, sourceId) {
  for (var i = 0; i < sources.length; i += 1) {
    if (sources[i].id === sourceId) {
      return i;
    }
  }
  return -1;
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
    sources: [],
    sourcesLoading: true,
    sourcesError: '',
    allMuted: false,
    version: config.version
  },

  onLoad: function () {
    theme.watchPage(this);
  },

  onUnload: function () {
    theme.unwatch(this);
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
      page.loadSources();
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

  loadSources: function () {
    var page = this;
    this.setData({ sourcesLoading: true, sourcesError: '' });
    return api.callUser('listMySources').then(function (data) {
      var sources = normalizeSources(data && data.sources);
      page.setData({
        sourcesLoading: false,
        sources: sources,
        allMuted: isAllMuted(sources)
      });
    }).catch(function (error) {
      page.setData({
        sourcesLoading: false,
        sourcesError: error.message || '来源列表加载失败'
      });
    });
  },

  retrySources: function () {
    this.loadSources();
  },

  toggleSource: function (event) {
    var page = this;
    var sourceId = event.currentTarget.dataset.id;
    var index = findSourceIndex(this.data.sources, sourceId);
    if (index < 0 || this.data.sources[index].pending) {
      return;
    }

    // switch 的 checked 表示「接收」，与后端存的「已关闭」相反。
    var muted = !event.detail.value;
    var previous = this.data.sources[index].muted;
    // 先按用户操作更新界面，请求失败再回滚：开关必须即时反馈，否则会被连点。
    this.patchSource(index, { muted: muted, pending: true });

    // 返回 promise：调用方（含测试）需要能等到请求真正结束，否则回滚还没发生就被观察。
    return api.callUser(muted ? 'muteSource' : 'unmuteSource', { sourceId: sourceId }).then(function () {
      page.patchSource(index, { pending: false });
    }).catch(function (error) {
      page.patchSource(index, { muted: previous, pending: false });
      wx.showToast({ title: error.message || '设置失败，请重试', icon: 'none' });
    });
  },

  patchSource: function (index, changes) {
    var sources = this.data.sources.slice();
    sources[index] = Object.assign({}, sources[index], changes);
    this.setData({ sources: sources, allMuted: isAllMuted(sources) });
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
