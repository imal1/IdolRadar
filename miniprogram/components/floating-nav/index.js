Component({
  properties: {
    current: {
      type: String,
      value: 'radar'
    }
  },

  data: {
    open: false,
    items: [
      { key: 'radar', label: '雷达', path: '/pages/radar/index' },
      { key: 'picker', label: '选她', path: '/pages/picker/index' },
      { key: 'me', label: '我的', path: '/pages/me/index' }
    ]
  },

  methods: {
    toggle: function () {
      this.setData({ open: !this.data.open });
    },

    close: function () {
      this.setData({ open: false });
    },

    navigate: function (event) {
      var key = event.currentTarget.dataset.key;
      var path = event.currentTarget.dataset.path;
      if (!path || key === this.properties.current) {
        this.close();
        return;
      }
      this.close();
      wx.reLaunch({ url: path });
    }
  }
});
