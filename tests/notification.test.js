'use strict';

process.env.TZ = 'Asia/Shanghai';

const assert = require('node:assert/strict');
const test = require('node:test');
const { loadContract } = require('./helpers/contract');

let message;

test.before(() => {
  message = loadContract('cloudfunctions/sendNotify/lib/message.js', [
    'buildSubscribeData',
    'formatMessageTime',
    'truncateThing'
  ]);
});

test('formatMessageTime uses subscription-template date format', () => {
  assert.equal(
    message.formatMessageTime(new Date('2026-07-22T12:34:00+08:00')),
    '2026-07-22 12:34'
  );
});

test('truncateThing counts Unicode code points instead of UTF-16 units', () => {
  assert.equal(message.truncateThing('一二三四五', 4), '一二三四');
  assert.equal(message.truncateThing('A😀BC', 3), 'A😀B');
  assert.equal(Array.from(message.truncateThing('一'.repeat(30))).length, 20);
});

test('buildSubscribeData maps idol, title, and time to approved field shape', () => {
  assert.deepEqual(
    message.buildSubscribeData('苏念', '发布了新动态', new Date('2026-07-22T12:34:00+08:00')),
    {
      thing1: { value: '苏念' },
      thing2: { value: '发布了新动态' },
      time3: { value: '2026-07-22 12:34' }
    }
  );
});

test('buildSubscribeData limits thing fields and supplies non-empty fallbacks', () => {
  const result = message.buildSubscribeData('', '😀'.repeat(30), new Date('2026-07-22T12:34:00+08:00'));
  assert.ok(result.thing1.value.length > 0);
  assert.ok(result.thing2.value.length > 0);
  assert.ok(Array.from(result.thing1.value).length <= 20);
  assert.ok(Array.from(result.thing2.value).length <= 20);
});
