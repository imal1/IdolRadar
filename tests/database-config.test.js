'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');

function readJson(relativePath) {
  return JSON.parse(fs.readFileSync(path.join(root, relativePath), 'utf8'));
}

function readJsonLines(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), 'utf8')
    .split(/\r?\n/)
    .filter((line) => line.trim())
    .map((line) => JSON.parse(line));
}

test('sample seeds are valid JSON Lines with safe non-routable URLs', () => {
  const idols = readJsonLines('database/idols.seed.jsonl');
  const sources = readJsonLines('database/sources.seed.jsonl');
  const idolIds = new Set(idols.map((idol) => idol._id));

  assert.ok(idols.length >= 2);
  assert.ok(sources.length >= 2);
  for (const source of sources) {
    assert.ok(idolIds.has(source.idolId));
    assert.equal(new URL(source.rssUrl).hostname.endsWith('example.invalid'), true);
  }
});

test('database rules deny every collection direct client access', () => {
  const rules = readJson('database/security-rules.json');
  for (const collection of ['idols', 'sources', 'posts', 'users']) {
    assert.deepEqual(rules[collection], { read: false, write: false });
  }
});

test('function rules expose only authenticated user API to clients', () => {
  const rules = readJson('database/function-security-rules.json');
  assert.deepEqual(rules['*'], { invoke: false });
  assert.deepEqual(rules.user, { invoke: "auth.loginType != 'ANONYMOUS' && auth != null" });
  assert.deepEqual(rules.fetchFeeds, { invoke: false });
  assert.deepEqual(rules.sendNotify, { invoke: false });
});

test('required unique indexes protect openid and post-link dedupe contracts', () => {
  const indexes = readJson('database/indexes.json');
  const postLink = indexes.posts.find((index) => index.name === 'link_unique');
  const postFeed = indexes.posts.find((index) => index.name === 'idolId_publishedAt');
  const userOpenid = indexes.users.find((index) => index.name === 'openid_unique');
  const notificationUsers = indexes.users.find((index) => index.name === 'idolId_subscribeQuota');
  const enabledSources = indexes.sources.find((index) => index.name === 'enabled_id');

  assert.equal(postLink.unique, true);
  assert.deepEqual(postLink.fields, [{ field: 'link', order: 'asc' }]);
  assert.equal(userOpenid.unique, true);
  assert.deepEqual(userOpenid.fields, [{ field: 'openid', order: 'asc' }]);
  assert.deepEqual(postFeed.fields, [
    { field: 'idolId', order: 'asc' },
    { field: 'publishedAt', order: 'desc' },
    { field: '_id', order: 'desc' }
  ]);
  assert.deepEqual(notificationUsers.fields, [
    { field: 'idolId', order: 'asc' },
    { field: 'subscribeQuota', order: 'asc' },
    { field: '_id', order: 'asc' }
  ]);
  assert.deepEqual(enabledSources.fields, [
    { field: 'enabled', order: 'asc' },
    { field: '_id', order: 'asc' }
  ]);
});
