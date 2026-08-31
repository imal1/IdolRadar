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

// 发布配置没有路径参数，测试临时改写仓库文件并在结束后原样还原。
function patchFile(t, filePath, patch) {
  const absolutePath = path.join(root, filePath);
  const original = fs.readFileSync(absolutePath);
  t.after(() => fs.writeFileSync(absolutePath, original));
  fs.writeFileSync(absolutePath, patch(original.toString('utf8')));
  return absolutePath;
}

function withPatchedFile(t, filePath, patch) {
  patchFile(t, filePath, patch);
  return spawnSync(
    process.execPath,
    ['scripts/validate-project.js', '--allow-placeholders'],
    { cwd: root, encoding: 'utf8' }
  );
}

function withPatchedCompose(t, patch) {
  return withPatchedFile(t, 'compose.yaml', patch);
}

function renderCompose(composePath, envPath) {
  return spawnSync(
    'docker',
    ['compose', '--env-file', envPath, '-f', composePath, 'config', '--format', 'json'],
    { cwd: root, encoding: 'utf8' }
  );
}

function evaluateNotificationGate(configJson) {
  return spawnSync(
    'jq',
    ['-e', '.services.worker.environment.IDOLRADAR_WORKER_NOTIFICATIONS_ENABLED == "true"'],
    { cwd: root, encoding: 'utf8', input: configJson }
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

test('release validation rejects publicly bound runtime metrics', (t) => {
  const result = withPatchedCompose(t, (compose) => compose.replace(
    '127.0.0.1:${APP_METRICS_PORT:-9090}:9090',
    '0.0.0.0:${APP_METRICS_PORT:-9090}:9090'
  ));

  assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stderr, /API 指标端口必须仅绑定 127\.0\.0\.1/);
});

test('release validation rejects enabling automatic notifications by default', (t) => {
  const result = withPatchedCompose(t, (compose) => compose.replace(
    '${NOTIFICATIONS_ENABLED:-false}',
    '${NOTIFICATIONS_ENABLED:-true}'
  ));

  assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stderr, /自动推送必须由 NOTIFICATIONS_ENABLED 显式开启，默认保持 false/);
});

test('production notification gate rejects a disabled final worker environment', (t) => {
  const workflow = fs.readFileSync(path.join(root, '.github/workflows/release.yml'), 'utf8');
  assert.match(
    workflow,
    /config --format json\s*\|\s*jq -e '\.services\.worker\.environment\.IDOLRADAR_WORKER_NOTIFICATIONS_ENABLED == "true"'/
  );

  const envDir = fs.mkdtempSync(path.join(os.tmpdir(), 'idolradar-production-env-'));
  t.after(() => fs.rmSync(envDir, { recursive: true, force: true }));
  const envPath = path.join(envDir, '.env');
  fs.writeFileSync(envPath, [
    'POSTGRES_PASSWORD=test-postgres',
    'REDIS_PASSWORD=test-redis',
    'WECHAT_APP_ID=wxtest',
    'WECHAT_APP_SECRET=test-secret',
    'NOTIFICATIONS_ENABLED=true',
    ''
  ].join('\n'));

  const enabledCompose = renderCompose(path.join(root, 'compose.yaml'), envPath);
  assert.ifError(enabledCompose.error);
  assert.equal(enabledCompose.status, 0, `${enabledCompose.stdout}\n${enabledCompose.stderr}`);
  const enabledGate = evaluateNotificationGate(enabledCompose.stdout);
  assert.ifError(enabledGate.error);
  assert.equal(enabledGate.status, 0, `${enabledGate.stdout}\n${enabledGate.stderr}`);

  const composePath = patchFile(t, 'compose.yaml', (compose) => compose.replace(
    '      APP_MODE: worker',
    '      APP_MODE: worker\n      IDOLRADAR_WORKER_NOTIFICATIONS_ENABLED: "false"'
  ));
  const disabledCompose = renderCompose(composePath, envPath);
  assert.ifError(disabledCompose.error);
  assert.equal(disabledCompose.status, 0, `${disabledCompose.stdout}\n${disabledCompose.stderr}`);
  const disabledGate = evaluateNotificationGate(disabledCompose.stdout);
  assert.ifError(disabledGate.error);
  assert.notEqual(disabledGate.status, 0, `${disabledGate.stdout}\n${disabledGate.stderr}`);

  const validationResult = withPatchedFile(
    t,
    '.github/workflows/release.yml',
    (source) => source.replace(
      'config --format json |\n            jq -e \'.services.worker.environment.IDOLRADAR_WORKER_NOTIFICATIONS_ENABLED == "true"\' >/dev/null',
      'config --environment |\n            grep -Fx \'NOTIFICATIONS_ENABLED=true\' >/dev/null'
    )
  );
  assert.notEqual(validationResult.status, 0, `${validationResult.stdout}\n${validationResult.stderr}`);
  assert.match(validationResult.stderr, /生产部署必须校验最终 Worker 环境/);
});

test('release validation rejects a nested fail-open production notification gate', (t) => {
  const result = withPatchedFile(t, '.github/workflows/release.yml', (workflow) => workflow.replace(
    'echo "::error::production/PRODUCTION_ENV_FILE 必须显式设置 NOTIFICATIONS_ENABLED=true"\n            exit 1',
    'echo "::error::production/PRODUCTION_ENV_FILE 必须显式设置 NOTIFICATIONS_ENABLED=true"\n            if false; then\n              exit 1\n            fi'
  ));

  assert.notEqual(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stderr, /生产通知门禁失败时必须 exit 1/);
});
