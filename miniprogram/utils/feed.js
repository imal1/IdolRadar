var time = require('./time');

// 在网络边界兼容历史字段名，页面组件只消费稳定的 Post 展示模型。
function text(value) {
  return typeof value === 'string' ? value.trim() : '';
}

function normalizePost(post, now) {
  post = post || {};
  var title = text(post.title) || '新的动态';
  var source = post.source && typeof post.source === 'object' ? post.source : {};
  var publishedAt = post.publishedAt || post.fetchedAt || '';

  return {
    id: String(post._id || post.id || post.postId || ''),
    title: title,
    summary: text(post.summary) || title,
    link: text(post.link),
    channel: text(post.channel) || text(post.sourceChannel) || text(source.channel) || '动态',
    publishedAt: publishedAt,
    timeText: time.formatRelativeTime(publishedAt, now)
  };
}

function normalizePosts(posts, now) {
  if (!Array.isArray(posts)) {
    return [];
  }
  return posts.filter(function (post) {
    return post && typeof post === 'object' && !Array.isArray(post);
  }).map(function (post) {
    return normalizePost(post, now);
  });
}

module.exports = {
  normalizePost: normalizePost,
  normalizePosts: normalizePosts
};
