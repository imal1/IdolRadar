var api = require('../../utils/api');
var feed = require('../../utils/feed');
var idolUtils = require('../../utils/idol');
var subscription = require('../../utils/subscription');

var PAGE_SIZE = 20;

function safeDecode(value) {
  if (!value) {
    return '';
  }
  try {
    return decodeURIComponent(value);
  } catch (error) {
    return String(value);
  }
}

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

function number(value) {
  value = Number(value);
  return isNaN(value) ? 0 : value;
}

function channelClass(channel) {
  var value = String(channel || '').toLowerCase();
  if (value.indexOf('b站') >= 0 || value.indexOf('bilibili') >= 0) {
    return 'channel-blue';
  }
  if (value.indexOf('行程') >= 0 || value.indexOf('schedule') >= 0) {
    return 'channel-green';
  }
  if (value.indexOf('微博') >= 0 || value.indexOf('weibo') >= 0) {
    return 'channel-pink';
  }
  return 'channel-purple';
}

function decoratePosts(posts) {
  return posts.map(function (post) {
    return Object.assign({}, post, { tagClass: channelClass(post.channel) });
  });
}

function uniquePosts(posts) {
  var seen = {};
  return posts.filter(function (post, index) {
    var key = post.id || post.link || (post.title + '|' + post.publishedAt + '|' + index);
    if (seen[key]) {
      return false;
    }
    seen[key] = true;
    return true;
  });
}

Page({
  data: {
    loading: true,
    refreshing: false,
    loadingMore: false,
    subscribing: false,
    errorMessage: '',
    idol: null,
    stats: {
      todayCount: 0,
      sourceCount: 0,
      signalStrength: '满格'
    },
    allPosts: [],
    latestPost: null,
    previousPosts: [],
    hasMore: false,
    nextCursor: null,
    subscribeQuota: 0,
    showSubscribeBanner: true,
    pageSize: PAGE_SIZE,
    deepLinkPostId: ''
  },

  onLoad: function (options) {
    this._shownOnce = false;
    this._dataRequestId = 0;
    this.setData({ deepLinkPostId: safeDecode(options && options.postId) });
    this.loadInitial().catch(function () {
      return null;
    });
  },

  onShow: function () {
    if (this._shownOnce && !this.data.loading && !this.data.refreshing) {
      this.loadInitial({ silent: true, forceBootstrap: true }).catch(function () {
        return null;
      });
    }
    this._shownOnce = true;
  },

  onPullDownRefresh: function () {
    var page = this;
    this.setData({ refreshing: true });
    this.loadInitial({ silent: true, forceBootstrap: true }).then(function () {
      wx.showToast({ title: '已感应到最新', icon: 'none' });
    }).catch(function () {
      return null;
    }).then(function () {
      page.setData({ refreshing: false });
      wx.stopPullDownRefresh();
    });
  },

  onReachBottom: function () {
    this.loadMore();
  },

  loadInitial: function (options) {
    options = options || {};
    var page = this;
    var app = getApp();
    var requestId = (this._dataRequestId || 0) + 1;
    this._dataRequestId = requestId;
    if (!options.silent) {
      this.setData({ loading: true, errorMessage: '' });
    }
    if (this.data.loadingMore) {
      this.setData({ loadingMore: false });
    }

    return app.ensureBootstrap({ force: Boolean(options.forceBootstrap) }).then(function (bootstrap) {
      if (page._dataRequestId !== requestId) {
        return null;
      }
      if (!getIdolId(bootstrap)) {
        wx.redirectTo({ url: '/pages/picker/index?mode=first' });
        return null;
      }
      return api.callUser('getHome');
    }).then(function (home) {
      if (!home || page._dataRequestId !== requestId) {
        return null;
      }
      if (!getRawIdol(home)) {
        app.invalidateBootstrap();
        wx.redirectTo({ url: '/pages/picker/index?mode=first' });
        return null;
      }
      page.applyHome(home);
      page.setData({ loading: false, errorMessage: '' });
      page.scrollToDeepLink();
      return home;
    }).catch(function (error) {
      if (page._dataRequestId !== requestId) {
        return null;
      }
      page.setData({
        loading: false,
        errorMessage: error.message || '雷达暂时没有信号，请稍后重试'
      });
      throw error;
    });
  },

  applyHome: function (home) {
    var app = getApp();
    var bootstrap = app.globalData.bootstrap || {};
    var rawIdol = getRawIdol(home) || getRawIdol(bootstrap) || {};
    var idol = idolUtils.normalizeIdol(rawIdol, 0);
    if (!idol.id) {
      idol.id = getIdolId(home) || getIdolId(bootstrap);
    }

    var rawFeed = home.feed && typeof home.feed === 'object' ? home.feed : home;
    var rawPosts = Array.isArray(rawFeed.posts) ? rawFeed.posts : [];
    if (home.latestPost) {
      rawPosts = [home.latestPost].concat(rawPosts);
    }
    var posts = uniquePosts(decoratePosts(feed.normalizePosts(rawPosts)));
    var user = getUser(home);
    var stats = home.stats || {};
    var sourceCount = idolUtils.firstDefined(
      stats.sourceCount,
      stats.sourcesCount,
      home.sourceCount,
      idol.sourceCount,
      0
    );
    var quota = number(idolUtils.firstDefined(user.subscribeQuota, home.subscribeQuota, 0));

    idol.sourceCount = number(sourceCount);
    this.setData({
      idol: idol,
      stats: {
        todayCount: number(idolUtils.firstDefined(stats.todayCount, stats.todayPostCount, stats.todayPosts, home.todayCount, 0)),
        sourceCount: number(sourceCount),
        signalStrength: String(stats.signalStrength || '满格')
      },
      allPosts: posts,
      latestPost: posts[0] || null,
      previousPosts: posts.slice(1),
      hasMore: Boolean(rawFeed.hasMore),
      nextCursor: rawFeed.nextCursor || null,
      subscribeQuota: quota,
      showSubscribeBanner: quota <= 0
    });
  },

  loadMore: function () {
    var page = this;
    if (this.data.loading || this.data.refreshing || this.data.loadingMore || !this.data.hasMore || !this.data.nextCursor) {
      return Promise.resolve();
    }

    var requestId = this._dataRequestId;
    this.setData({ loadingMore: true });
    return api.callUser('getFeed', { cursor: this.data.nextCursor }).then(function (result) {
      if (page._dataRequestId !== requestId || page.data.refreshing) {
        return null;
      }
      result = result || {};
      var incoming = decoratePosts(feed.normalizePosts(result.posts));
      var posts = uniquePosts(page.data.allPosts.concat(incoming));
      page.setData({
        allPosts: posts,
        latestPost: posts[0] || null,
        previousPosts: posts.slice(1),
        hasMore: Boolean(result.hasMore),
        nextCursor: result.nextCursor || null,
        loadingMore: false
      });
    }).catch(function (error) {
      if (page._dataRequestId !== requestId || page.data.refreshing) {
        return null;
      }
      page.setData({ loadingMore: false });
      wx.showToast({ title: error.message || '加载失败，请重试', icon: 'none' });
    });
  },

  scrollToDeepLink: function () {
    if (!this.data.deepLinkPostId || !this.data.latestPost) {
      return;
    }
    setTimeout(function () {
      wx.pageScrollTo({ selector: '#latest-post', duration: 300 });
    }, 120);
  },

  retry: function () {
    this.loadInitial({ forceBootstrap: true }).catch(function () {
      return null;
    });
  },

  changeIdol: function () {
    wx.navigateTo({ url: '/pages/picker/index' });
  },

  avatarError: function () {
    if (!this.data.idol) {
      return;
    }
    this.setData({ 'idol.avatarVisible': false });
  },

  copyLink: function (event) {
    var link = event.currentTarget.dataset.link;
    if (!link) {
      wx.showToast({ title: '原文链接暂不可用', icon: 'none' });
      return;
    }
    wx.setClipboardData({
      data: link,
      success: function () {
        wx.showToast({ title: '原文链接已复制', icon: 'success' });
      },
      fail: function () {
        wx.showToast({ title: '复制失败，请重试', icon: 'none' });
      }
    });
  },

  enableSubscription: function () {
    var page = this;
    if (this.data.subscribing) {
      return;
    }
    this.setData({ subscribing: true });
    subscription.requestSubscription().then(function (accepted) {
      if (accepted) {
        page.setData({
          subscribeQuota: page.data.subscribeQuota + 1,
          showSubscribeBanner: false
        });
      }
    }).catch(function (error) {
      wx.showToast({ title: error.message || '开启失败，请重试', icon: 'none' });
    }).then(function () {
      page.setData({ subscribing: false });
    });
  }
});
