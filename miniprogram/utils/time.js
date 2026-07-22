function pad(value) {
  return value < 10 ? '0' + value : String(value);
}

function toDate(value) {
  if (value === null || value === undefined || value === '') {
    return null;
  }
  if (value instanceof Date) {
    return new Date(value.getTime());
  }
  if (value && typeof value === 'object') {
    if (value.$date) {
      return toDate(value.$date);
    }
    if (typeof value._seconds === 'number') {
      return new Date(value._seconds * 1000);
    }
    if (typeof value.seconds === 'number') {
      return new Date(value.seconds * 1000);
    }
  }
  var date = new Date(value);
  return isNaN(date.getTime()) ? null : date;
}

function startOfDay(date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
}

function formatClock(date) {
  return pad(date.getHours()) + ':' + pad(date.getMinutes());
}

function formatRelativeTime(input, nowInput) {
  var date = toDate(input);
  var now = toDate(nowInput) || new Date();
  if (!date) {
    return '时间未知';
  }

  var dateDay = startOfDay(date);
  var nowDay = startOfDay(now);
  var dayDiff = Math.round((nowDay - dateDay) / 86400000);
  var diff = now.getTime() - date.getTime();

  if (dayDiff <= 0) {
    if (diff < 60000) {
      return '刚刚';
    }
    if (diff < 3600000) {
      return Math.max(1, Math.floor(diff / 60000)) + ' 分钟前';
    }
    return Math.max(1, Math.floor(diff / 3600000)) + ' 小时前';
  }
  if (dayDiff === 1) {
    return '昨天 ' + formatClock(date);
  }
  if (dayDiff === 2) {
    return '前天 ' + formatClock(date);
  }
  return (date.getMonth() + 1) + '月' + date.getDate() + '日 ' + formatClock(date);
}

function formatDateTime(input) {
  var date = toDate(input);
  if (!date) {
    return '时间未知';
  }
  return date.getFullYear() + '-' + pad(date.getMonth() + 1) + '-' + pad(date.getDate()) + ' ' + formatClock(date);
}

module.exports = {
  formatRelativeTime: formatRelativeTime,
  formatDateTime: formatDateTime,
  toDate: toDate
};
