var api = require('../../utils/api');
var idolUtils = require('../../utils/idol');
var theme = require('../../utils/theme');

function getUser(data) {
  return data && data.user && typeof data.user === 'object' ? data.user : (data || {});
}

function getCurrentIdolId(data) {
  data = data || {};
  var user = getUser(data);
  var idol = data.idol || data.currentIdol || user.idol || {};
  return String(idol._id || idol.id || data.idolId || user.idolId || '');
}

function confirmSwitch(name) {
  return new Promise(function (resolve) {
    wx.showModal({
      title: '确认更换守护对象？',
      content: '换人后旧动态将不再展示，推送也会切换到' + name + '。',
      confirmText: '确认更换',
      confirmColor: '#c4526e',
      success: function (result) {
        resolve(Boolean(result.confirm));
      },
      fail: function () {
        resolve(false);
      }
    });
  });
}

var REQUEST_NAME_MAX_LENGTH = 64;
var REQUEST_STATUS_LABELS = { pending: '审核中', approved: '已上线', rejected: '未通过' };

Page({
  data: {
    loading: true,
    errorMessage: '',
    query: '',
    idols: [],
    filteredIdols: [],
    currentIdolId: '',
    confirmingId: '',
    selectingId: '',
    myRequests: [],
    submittingRequest: false
  },

  onLoad: function () {
    theme.watchPage(this);
    this.loadData();
    this.loadMyRequests();
  },

  onUnload: function () {
    theme.unwatch(this);
  },

  loadData: function () {
    var page = this;
    var app = getApp();
    this.setData({ loading: true, errorMessage: '' });

    // 初始化用户与候选列表并行读取，最终以服务端 currentIdolId 为准。
    return Promise.all([
      app.ensureBootstrap({ force: true }),
      api.callUser('listIdols')
    ]).then(function (results) {
      var bootstrap = results[0] || {};
      var response = results[1] || {};
      var rawIdols = Array.isArray(response) ? response : (response.idols || []);
      var idols = rawIdols.map(function (idol, index) {
        return idolUtils.normalizeIdol(idol, index);
      }).filter(function (idol) {
        return Boolean(idol.id);
      });
      page.setData({
        loading: false,
        currentIdolId: String(response.currentIdolId || getCurrentIdolId(bootstrap) || ''),
        idols: idols,
        filteredIdols: idols
      });
      page.applyFilter(page.data.query);
    }).catch(function (error) {
      page.setData({
        loading: false,
        errorMessage: error.message || '守护名单加载失败，请稍后重试'
      });
    });
  },

  retry: function () {
    this.loadData();
    this.loadMyRequests();
  },

  // 申请状态是次要信息：读取失败只留空，不打断选人主流程。
  loadMyRequests: function () {
    var page = this;
    return api.callUser('listMyIdolRequests').then(function (data) {
      var requests = (data && data.requests) || [];
      page.setData({
        myRequests: requests.map(function (request) {
          return Object.assign({}, request, {
            statusLabel: REQUEST_STATUS_LABELS[request.status] || request.status
          });
        })
      });
    }).catch(function () {
      page.setData({ myRequests: [] });
    });
  },

  submitRequest: function () {
    var page = this;
    if (this.data.submittingRequest) {
      return;
    }
    wx.showModal({
      title: '申请新增 idol',
      editable: true,
      placeholderText: '输入想守护的名字',
      content: this.data.query || '',
      confirmText: '提交申请',
      confirmColor: '#c4526e',
      success: function (result) {
        if (!result.confirm) {
          return;
        }
        var name = String(result.content || '').trim();
        if (!name) {
          wx.showToast({ title: '请先填写名字', icon: 'none' });
          return;
        }
        // 服务端同样限制长度；这里提前拦下，省一次注定失败的请求。
        if (name.length > REQUEST_NAME_MAX_LENGTH) {
          wx.showToast({ title: '名字最多 ' + REQUEST_NAME_MAX_LENGTH + ' 个字', icon: 'none' });
          return;
        }
        page.setData({ submittingRequest: true });
        api.callUser('submitIdolRequest', { name: name }).then(function (data) {
          page.setData({ submittingRequest: false });
          var supporters = (data && data.supporterCount) || 1;
          wx.showToast({ title: '已收到，' + supporters + ' 人在等她', icon: 'none' });
          page.loadMyRequests();
        }).catch(function (error) {
          page.setData({ submittingRequest: false });
          wx.showToast({ title: error.message || '提交失败，请稍后重试', icon: 'none' });
        });
      }
    });
  },

  search: function (event) {
    var query = event.detail.value || '';
    this.setData({ query: query });
    this.applyFilter(query);
  },

  clearSearch: function () {
    this.setData({ query: '' });
    this.applyFilter('');
  },

  applyFilter: function (query) {
    var keyword = String(query || '').trim().toLowerCase();
    var filtered = this.data.idols.filter(function (idol) {
      return !keyword || idol.name.toLowerCase().indexOf(keyword) >= 0;
    });
    this.setData({ filteredIdols: filtered });
  },

  avatarError: function (event) {
    var id = event.currentTarget.dataset.id;
    var idols = this.data.idols.map(function (idol) {
      if (idol.id === id) {
        return Object.assign({}, idol, { avatarVisible: false });
      }
      return idol;
    });
    this.setData({ idols: idols });
    this.applyFilter(this.data.query);
  },

  selectIdol: function (event) {
    var page = this;
    var id = event.currentTarget.dataset.id;
    var selected = this.data.idols.filter(function (idol) {
      return idol.id === id;
    })[0];

    // confirmingId/selectingId 同时充当 UI 状态与防重复提交锁。
    if (!selected || id === this.data.currentIdolId || this.data.confirmingId || this.data.selectingId) {
      return;
    }

    this.setData({ confirmingId: id });
    var confirmation = this.data.currentIdolId ? confirmSwitch(selected.name) : Promise.resolve(true);
    confirmation.then(function (confirmed) {
      if (!confirmed) {
        page.setData({ confirmingId: '' });
        return null;
      }
      page.setData({ confirmingId: '', selectingId: id });
      return api.callUser('setIdol', { idolId: id }).then(function () {
        getApp().invalidateBootstrap();
        wx.showToast({ title: '开始守护' + selected.name, icon: 'success' });
        setTimeout(function () {
          wx.reLaunch({ url: '/pages/radar/index' });
        }, 320);
      }).catch(function (error) {
        page.setData({ confirmingId: '', selectingId: '' });
        wx.showToast({ title: error.message || '守护失败，请重试', icon: 'none' });
      });
    }).catch(function () {
      page.setData({ confirmingId: '', selectingId: '' });
      wx.showToast({ title: '操作未完成，请重试', icon: 'none' });
    });
  }
});
