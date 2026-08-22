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
  assert.match(result.stderr, /WECHAT_APP_SECRET 仍是占位值或未配置/);
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

// 模板 ID 与字段序号写在 compose.yaml 里，校验器没有路径参数，只能临时改写仓库文件再还原。
function withPatchedCompose(t, patch) {
  const composePath = path.join(root, 'compose.yaml');
  const original = fs.readFileSync(composePath);
  t.after(() => fs.writeFileSync(composePath, original));
  fs.writeFileSync(composePath, patch(original.toString('utf8')));
  return spawnSync(
    process.execPath,
    ['scripts/validate-project.js', '--allow-placeholders'],
    { cwd: root, encoding: 'utf8' }
  );
}

test('release validation rejects mismatched WeChat subscribe field types', (t) => {
  const result = withPatchedCompose(t, (compose) => compose.replace(
    /^(\s+IDOLRADAR_WORKER_SUBSCRIBE_IDOL_FIELD:).*$/m,
    '$1 time1'
  ));

  assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stderr, /IDOLRADAR_WORKER_SUBSCRIBE_IDOL_FIELD 必须是字面量 thing<number>/);
});

test('release validation rejects subscribe template id taken from .env', (t) => {
  const result = withPatchedCompose(t, (compose) => compose.replace(
    /^(\s+IDOLRADAR_WORKER_SUBSCRIBE_TEMPLATE_ID:).*$/m,
    '$1 ${SUBSCRIBE_TEMPLATE_ID:-}'
  ));

  assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stderr, /IDOLRADAR_SUBSCRIBE_TEMPLATE_ID \/ IDOLRADAR_WORKER_SUBSCRIBE_TEMPLATE_ID 必须是字面量/);
});

test('release validation rejects client and server template id drift', (t) => {
  // app 与 worker 必须一起改，否则先命中的是「两个服务不一致」这条规则。
  const result = withPatchedCompose(t, (compose) => compose.replace(
    /^(\s+IDOLRADAR(?:_WORKER)?_SUBSCRIBE_TEMPLATE_ID:).*$/gm,
    '$1 NOT-THE-CLIENT-TEMPLATE-ID'
  ));

  assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stderr, /subscribeTemplateId 与 compose.yaml IDOLRADAR_SUBSCRIBE_TEMPLATE_ID \/ IDOLRADAR_WORKER_SUBSCRIBE_TEMPLATE_ID 不一致/);
});
