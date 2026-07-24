'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const { isNight } = require('../miniprogram/utils/theme');

test('isNight is true from 00:00 through 05:59', () => {
  assert.equal(isNight(new Date(2026, 6, 24, 0, 0)), true);
  assert.equal(isNight(new Date(2026, 6, 24, 3, 30)), true);
  assert.equal(isNight(new Date(2026, 6, 24, 5, 59)), true);
});

test('isNight is false from 06:00 through 23:59', () => {
  assert.equal(isNight(new Date(2026, 6, 24, 6, 0)), false);
  assert.equal(isNight(new Date(2026, 6, 24, 12, 0)), false);
  assert.equal(isNight(new Date(2026, 6, 24, 23, 59)), false);
});
