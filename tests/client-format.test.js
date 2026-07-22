'use strict';

process.env.TZ = 'Asia/Shanghai';

const assert = require('node:assert/strict');
const test = require('node:test');

const {
  formatDateTime,
  formatRelativeTime,
  toDate
} = require('../miniprogram/utils/time');
const { normalizePost, normalizePosts } = require('../miniprogram/utils/feed');

const now = new Date('2026-07-22T12:00:00+08:00');

test('formatRelativeTime formats current-day relative times', () => {
  assert.equal(formatRelativeTime('2026-07-22T11:59:30+08:00', now), '刚刚');
  assert.equal(formatRelativeTime('2026-07-22T11:42:00+08:00', now), '18 分钟前');
  assert.equal(formatRelativeTime('2026-07-22T09:00:00+08:00', now), '3 小时前');
});

test('formatRelativeTime formats yesterday, day before yesterday, and older dates', () => {
  assert.equal(formatRelativeTime('2026-07-21T20:14:00+08:00', now), '昨天 20:14');
  assert.equal(formatRelativeTime('2026-07-20T22:40:00+08:00', now), '前天 22:40');
  assert.equal(formatRelativeTime('2026-07-18T08:05:00+08:00', now), '7月18日 08:05');
});

test('time helpers accept CloudBase timestamps and degrade invalid input', () => {
  assert.equal(toDate({ _seconds: 1784692800 }).toISOString(), '2026-07-22T04:00:00.000Z');
  assert.equal(formatDateTime('2026-07-22T12:34:00+08:00'), '2026-07-22 12:34');
  assert.equal(formatDateTime('not-a-date'), '时间未知');
  assert.equal(formatRelativeTime(null, now), '时间未知');
});

test('normalizePost exposes stable feed view fields and source aliases', () => {
  const publishedAt = '2026-07-22T11:42:00+08:00';
  const result = normalizePost({
    _id: 123,
    title: '  新动态  ',
    summary: '  摘要  ',
    link: '  https://example.com/post/1  ',
    source: { channel: '微博' },
    publishedAt
  }, now);

  assert.deepEqual(result, {
    id: '123',
    title: '新动态',
    summary: '摘要',
    link: 'https://example.com/post/1',
    channel: '微博',
    publishedAt,
    timeText: '18 分钟前'
  });
});

test('normalizePost applies documented fallbacks without inventing a link', () => {
  const fetchedAt = '2026-07-21T20:14:00+08:00';
  assert.deepEqual(normalizePost({ id: 'post-2', fetchedAt }, now), {
    id: 'post-2',
    title: '新的动态',
    summary: '新的动态',
    link: '',
    channel: '动态',
    publishedAt: fetchedAt,
    timeText: '昨天 20:14'
  });
});

test('normalizePosts ignores non-object records and preserves input order', () => {
  const result = normalizePosts([
    null,
    'bad record',
    { postId: 'a', title: 'A', sourceChannel: 'B站' },
    [],
    { id: 'b', title: 'B', channel: 'ins' }
  ], now);

  assert.deepEqual(result.map((post) => post.id), ['a', 'b']);
  assert.deepEqual(result.map((post) => post.channel), ['B站', 'ins']);
  assert.deepEqual(normalizePosts(null, now), []);
});
