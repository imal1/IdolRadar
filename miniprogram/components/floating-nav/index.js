Component({
  properties: {
    current: {
      type: String,
      value: 'radar'
    }
  },

  data: {
    open: false,
    dragX: 0,
    dragY: 0,
    snapping: false,
    items: [
      { key: 'radar', label: '动态', path: '/pages/radar/index' },
      { key: 'picker', label: '选 idol', path: '/pages/picker/index' },
      { key: 'me', label: '我的', path: '/pages/me/index' }
    ]
  },

  attached: function () {
    var info = wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync();
    this._winHeight = info.windowHeight;
    this._offY = 0;
  },

  methods: {
    toggle: function () {
      this.setData({ open: !this.data.open });
    },

    dragStart: function (event) {
      var touch = event.touches[0];
      this._startX = touch.clientX;
      this._startY = touch.clientY;
      this._moved = false;
      this.setData({ snapping: false });
    },

    dragMove: function (event) {
      var touch = event.touches[0];
      var dx = touch.clientX - this._startX;
      var dy = touch.clientY - this._startY;
      if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
        this._moved = true;
      }
      this.setData({
        dragX: Math.min(dx, 0),
        dragY: this._offY + dy
      });
    },

    dragEnd: function () {
      var comp = this;
      if (!this._moved) {
        this.setData({
          snapping: true,
          dragX: 0,
          dragY: this._offY,
          open: !this.data.open
        });
        return;
      }
      this._offY = this.data.dragY || 0;
      this.setData({ snapping: true, dragX: 0 });
      this.createSelectorQuery().select('.nav-trigger').boundingClientRect(function (rect) {
        if (!rect) {
          return;
        }
        var margin = 80;
        if (rect.top < margin) {
          comp._offY += margin - rect.top;
        } else if (rect.bottom > comp._winHeight - margin) {
          comp._offY -= rect.bottom - (comp._winHeight - margin);
        }
        comp.setData({ dragY: comp._offY });
      }).exec();
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
