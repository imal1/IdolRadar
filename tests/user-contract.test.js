'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { AppError, fail } = require('../cloudfunctions/user/lib/errors');
const { decodeCursor, encodeCursor } = require('../cloudfunctions/user/lib/pagination');
const { startOfShanghaiDay } = require('../cloudfunctions/user/lib/time');

test('feed cursor round-trips the stable publishedAt and id boundary', () => {
  const cursor = encodeCursor({
    _id: 'post-20',
    publishedAt: new Date('2026-07-22T04:12:00.000Z')
  });
  const decoded = decodeCursor(cursor);

  assert.match(cursor, /^[A-Za-z0-9_-]+$/);
  assert.equal(decoded.id, 'post-20');
  assert.equal(decoded.publishedAt.toISOString(), '2026-07-22T04:12:00.000Z');
});

test('feed cursor rejects malformed and oversized client input', () => {
  for (const cursor of ['not+base64', 'A'.repeat(513), Buffer.from('{"v":2}').toString('base64url')]) {
    assert.throws(
      () => decodeCursor(cursor),
      (error) => error instanceof AppError && error.code === 'INVALID_CURSOR'
    );
  }
});

test('Shanghai natural-day boundary is independent of host timezone', () => {
  assert.equal(
    startOfShanghaiDay(new Date('2026-07-22T15:59:59.999Z')).toISOString(),
    '2026-07-21T16:00:00.000Z'
  );
  assert.equal(
    startOfShanghaiDay(new Date('2026-07-22T16:00:00.000Z')).toISOString(),
    '2026-07-22T16:00:00.000Z'
  );
});

test('user API exposes business errors but hides internal details', () => {
  assert.deepEqual(fail(new AppError('INVALID_CURSOR', '分页参数无效')), {
    ok: false,
    error: { code: 'INVALID_CURSOR', message: '分页参数无效' }
  });
  assert.deepEqual(fail(new Error('database password leaked')), {
    ok: false,
    error: { code: 'INTERNAL_ERROR', message: '服务暂时不可用' }
  });
});
