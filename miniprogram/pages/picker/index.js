var api = require('../../utils/api');
var idolUtils = require('../../utils/idol');

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

Page({
  data: {
    loading: true,
    errorMessage: '',
    query: '',
    idols: [],
    filteredIdols: [],
    currentIdolId: '',
    confirmingId: '',
    selectingId: ''
  },

  onLoad: function () {
    this.loadData();
  },

  loadData: function () {
    var page = this;
    var app = getApp();
    this.setData({ loading: true, errorMessage: '' });

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
