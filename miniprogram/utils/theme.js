// Theme follows the system light/dark setting. WeChat only reports `theme`
// and fires wx.onThemeChange when app.json sets "darkmode": true.
function getTheme() {
  if (typeof wx === 'undefined') {
    return null;
  }
  if (wx.getAppBaseInfo) {
    return wx.getAppBaseInfo().theme || 'light';
  }
  if (wx.getSystemInfoSync) {
    return wx.getSystemInfoSync().theme || 'light';
  }
  return 'light';
}

function isNight(now) {
  var theme = getTheme();
  if (theme) {
    return theme === 'dark';
  }
  // 非小程序环境（如 Node 单测）没有 wx，回退到历史夜间时段规则以兼容既有测试；
  // 仅保留 `< 6` 判断即可完整覆盖 00:00-05:59（`>= 0` 对 Date.getHours() 恒成立）。
  var current = now instanceof Date ? now : new Date();
  return current.getHours() < 6;
}

// Overscroll/window background so the pull-down area matches the theme.
function setWindowBackground(night) {
  if (typeof wx === 'undefined' || !wx.setBackgroundColor) {
    return;
  }
  wx.setBackgroundColor(night
    ? { backgroundColor: '#171426', backgroundColorTop: '#171426', backgroundColorBottom: '#1c1524' }
    : { backgroundColor: '#f7f3f1', backgroundColorTop: '#fdf3f0', backgroundColorBottom: '#f7f3f1' });
}

// Register a theme listener on `target` (page or component). Fires `onChange`
// with the current value immediately, then again on every system theme change.
// Pair with unwatch() in onUnload/detached so the handler is not leaked.
function watch(target, onChange) {
  onChange(isNight());
  if (typeof wx === 'undefined' || !wx.onThemeChange) {
    return;
  }
  target.__themeHandler = function (res) {
    onChange(res.theme === 'dark');
  };
  wx.onThemeChange(target.__themeHandler);
}

function unwatch(target) {
  if (target.__themeHandler && typeof wx !== 'undefined' && wx.offThemeChange) {
    wx.offThemeChange(target.__themeHandler);
  }
  target.__themeHandler = null;
}

// Convenience for pages: keep the `night` flag and window background in sync
// with the system theme for as long as the page is alive.
function watchPage(page) {
  watch(page, function (night) {
    page.setData({ night: night });
    setWindowBackground(night);
  });
}

module.exports = {
  isNight: isNight,
  watch: watch,
  unwatch: unwatch,
  watchPage: watchPage
};
