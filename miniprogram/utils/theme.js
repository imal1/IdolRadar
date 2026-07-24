// Night mode is time-fixed: 00:00–06:00 local time shows the dark theme.
function isNight(now) {
  var hour = (now || new Date()).getHours();
  return hour >= 0 && hour < 6;
}

// Set page `night` flag + window overscroll background so the whole surface
// (including pull-down area) matches the theme.
function apply(page) {
  var night = isNight();
  page.setData({ night: night });
  if (wx.setBackgroundColor) {
    wx.setBackgroundColor(night
      ? { backgroundColor: '#171426', backgroundColorTop: '#171426', backgroundColorBottom: '#1c1524' }
      : { backgroundColor: '#f7f3f1', backgroundColorTop: '#fdf3f0', backgroundColorBottom: '#f7f3f1' });
  }
  return night;
}

module.exports = { isNight: isNight, apply: apply };
