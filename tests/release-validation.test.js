'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const test = require('node:test');

const root = path.resolve(__dirname, '..');

test('project structure and JSON pass pre-secret release validation', () => {
  const result = spawnSync(
    process.execPath,
    ['scripts/validate-project.js', '--allow-placeholders'],
    { cwd: root, encoding: 'utf8' }
  );

  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, /发布校验通过/);
  assert.match(result.stderr, /SUBSCRIBE_TEMPLATE_ID 仍是占位值或未配置/);
});

test('release validation rejects unsafe production RSS URLs', (t) => {
  const seedDir = fs.mkdtempSync(path.join(os.tmpdir(), 'idolradar-seeds-'));
  t.after(() => fs.rmSync(seedDir, { recursive: true, force: true }));
  fs.writeFileSync(
    path.join(seedDir, 'idols.seed.jsonl'),
    '{"_id":"idol-safe","name":"授权测试对象","avatar":"https://cdn.example.com/a.png","bio":"test","enabled":true}\n'
  );
  fs.writeFileSync(
    path.join(seedDir, 'sources.seed.jsonl'),
    '{"_id":"source-unsafe","idolId":"idol-safe","rssUrl":"https://127.0.0.1/feed.xml","channel":"RSS","enabled":true}\n'
  );

  const result = spawnSync(
    process.execPath,
    ['scripts/validate-project.js', '--allow-placeholders', '--seed-dir', seedDir],
    { cwd: root, encoding: 'utf8' }
  );

  assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stderr, /rssUrl 不安全或无效/);
});

test('release validation rejects mismatched WeChat subscribe field types', () => {
  const result = spawnSync(
    process.execPath,
    ['scripts/validate-project.js', '--allow-placeholders'],
    {
      cwd: root,
      encoding: 'utf8',
      env: Object.assign({}, process.env, {
        SUBSCRIBE_IDOL_FIELD: 'time1',
        SUBSCRIBE_TITLE_FIELD: 'thing2',
        SUBSCRIBE_TIME_FIELD: 'time3'
      })
    }
  );

  assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stderr, /SUBSCRIBE_IDOL_FIELD 必须匹配 thing<number>/);
});
