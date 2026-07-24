#!/usr/bin/env node

'use strict';

const fs = require('node:fs');
const net = require('node:net');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const allowPlaceholders = process.argv.includes('--allow-placeholders');
const seedDirFlag = process.argv.indexOf('--seed-dir');
if (seedDirFlag >= 0 && !process.argv[seedDirFlag + 1]) {
  console.error('ERROR --seed-dir 后必须提供目录路径');
  process.exit(1);
}
const seedDir = seedDirFlag >= 0
  ? path.resolve(process.argv[seedDirFlag + 1])
  : path.join(root, 'database');
const errors = [];
const warnings = [];

function relative(filePath) {
  return path.relative(root, filePath) || '.';
}

function addError(message) {
  errors.push(message);
}

function placeholder(message) {
  if (allowPlaceholders) warnings.push(message);
  else addError(message);
}

function requireFile(filePath) {
  const absolute = path.join(root, filePath);
  if (!fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) {
    addError(`缺少文件：${filePath}`);
    return false;
  }
  return true;
}

function parseJson(filePath) {
  try {
    return JSON.parse(fs.readFileSync(filePath, 'utf8'));
  } catch (error) {
    addError(`${relative(filePath)} 不是合法 JSON：${error.message}`);
    return null;
  }
}

function parseJsonLines(filePath) {
  const records = [];
  let lines;
  try {
    lines = fs.readFileSync(filePath, 'utf8').split(/\r?\n/);
  } catch (error) {
    addError(`${relative(filePath)} 无法读取：${error.message}`);
    return records;
  }
  lines.forEach((line, index) => {
    if (!line.trim()) return;
    try {
      const record = JSON.parse(line);
      if (!record || Array.isArray(record) || typeof record !== 'object') {
        throw new Error('每行必须是一个 JSON 对象');
      }
      records.push(record);
    } catch (error) {
      addError(`${relative(filePath)}:${index + 1} 不是合法 JSON Lines：${error.message}`);
    }
  });
  if (records.length === 0) addError(`${relative(filePath)} 不含任何记录`);
  return records;
}

function walkJson(directory) {
  if (!fs.existsSync(directory)) return;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (['node_modules', '.git', 'graphify-out'].includes(entry.name)) continue;
    const filePath = path.join(directory, entry.name);
    if (entry.isDirectory()) walkJson(filePath);
    else if (entry.isFile() && entry.name.endsWith('.json')) parseJson(filePath);
  }
}

function loadDotenv() {
  const values = {};
  for (const name of ['.env', '.env.local']) {
    const filePath = path.join(root, name);
    if (!fs.existsSync(filePath)) continue;
    for (const line of fs.readFileSync(filePath, 'utf8').split(/\r?\n/)) {
      const match = line.match(/^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*$/);
      if (!match) continue;
      let value = match[2];
      if ((value.startsWith('"') && value.endsWith('"'))
        || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
      values[match[1]] = value;
    }
  }
  return values;
}

function environmentValue(environment, names) {
  for (const name of names) {
    const value = environment[name];
    if (typeof value === 'string' && value.trim()) return value.trim();
  }
  return '';
}

function isPrivateApiHostname(hostname) {
  const normalized = hostname.replace(/^\[|\]$/g, '').toLowerCase();
  if (net.isIP(normalized)) return true;
  if (!normalized.includes('.')) return true;
  const reserved = [
    'localhost', 'example.com', 'example.net', 'example.org',
    'invalid', 'test', 'example'
  ];
  if (reserved.some((name) => normalized === name || normalized.endsWith(`.${name}`))) return true;
  return ['.local', '.lan', '.home', '.internal']
    .some((suffix) => normalized === suffix.slice(1) || normalized.endsWith(suffix));
}

function inspectApiBaseUrl(value) {
  const url = new URL(value);
  const originOnly = !url.username
    && !url.password
    && url.pathname === '/'
    && !url.search
    && !url.hash;
  const supportedProtocol = url.protocol === 'http:' || url.protocol === 'https:';
  const productionReady = url.protocol === 'https:' && !isPrivateApiHostname(url.hostname);
  return { originOnly, productionReady, supportedProtocol, url };
}

function isPlaceholder(value) {
  if (typeof value !== 'string') return true;
  const normalized = value.trim().toLowerCase();
  return !normalized
    || normalized === 'touristappid'
    || normalized.includes('example.invalid')
    || normalized.includes('your_')
    || normalized.includes('replace_me')
    || normalized.includes('replace-with')
    || normalized.includes('replace_with')
    || normalized.includes('请替换');
}

const requiredFiles = [
  'package.json',
  'package-lock.json',
  '.env.example',
  'Dockerfile',
  'compose.yaml',
  'compose.prod.yaml',
  'project.config.json',
  'miniprogram/app.js',
  'miniprogram/app.json',
  'miniprogram/app.wxss',
  'miniprogram/config/env.js',
  'miniprogram/utils/api.js',
  'backend/pom.xml',
  'backend/src/main/java/com/idolradar/IdolRadarApplication.java',
  'backend/src/main/java/com/idolradar/web/ApiController.java',
  'backend/src/main/java/com/idolradar/auth/AuthService.java',
  'backend/src/main/java/com/idolradar/worker/WorkerService.java',
  'backend/src/main/java/com/idolradar/web/PreAuthRateLimitInterceptor.java',
  'backend/src/main/resources/application.yml',
  'backend/src/main/resources/db/migration/V1__init.sql',
  'backend/src/main/resources/db/migration/V2__notification_delivery_state.sql',
  'backend/src/main/resources/db/migration/V3__notification_outbox.sql',
  'database/idols.seed.jsonl',
  'database/sources.seed.jsonl'
];
for (const filePath of requiredFiles) requireFile(filePath);
walkJson(root);

const packagePath = path.join(root, 'package.json');
if (fs.existsSync(packagePath)) {
  const packageJson = parseJson(packagePath);
  if (packageJson) {
    if (Object.keys(packageJson.dependencies || {}).length > 0) {
      addError('package.json: 不得包含 Node.js 生产后端依赖');
    }
    if (!/^>=24(?:\.0\.0)?$/.test(packageJson.engines?.node || '')) {
      addError('package.json: 可选客户端校验工具要求 Node.js >=24.0.0');
    }
  }
}

const outboxMigrationPath = path.join(
  root,
  'backend/src/main/resources/db/migration/V3__notification_outbox.sql'
);
if (fs.existsSync(outboxMigrationPath)) {
  const migration = fs.readFileSync(outboxMigrationPath, 'utf8');
  if (!/CREATE TABLE notification_outbox\b/i.test(migration)
    || !/status IN \('pending', 'processing', 'retryable', 'completed'\)/i.test(migration)) {
    addError('V3__notification_outbox.sql: 缺少持久化通知 outbox 或状态约束');
  }
}

for (const legacyPath of [
  'server/src/index.js',
  'server/src/app.js',
  'cloudfunctions/fetchFeeds/index.js',
  'cloudfunctions/sendNotify/index.js',
  'cloudfunctions/user/index.js',
  'cloudbaserc.json'
]) {
  if (fs.existsSync(path.join(root, legacyPath))) {
    addError(`仓库仍含旧 Node/CloudBase 后端：${legacyPath}`);
  }
}

const pomPath = path.join(root, 'backend/pom.xml');
if (fs.existsSync(pomPath)) {
  const pom = fs.readFileSync(pomPath, 'utf8');
  if (!/<java\.version>21<\/java\.version>/.test(pom)) {
    addError('backend/pom.xml: Java 版本必须为 21');
  }
  for (const artifact of [
    'spring-boot-starter-webmvc',
    'spring-boot-starter-jdbc',
    'spring-boot-starter-data-redis',
    'spring-boot-starter-flyway',
    'flyway-database-postgresql',
    'postgresql',
    'httpclient5'
  ]) {
    if (!pom.includes(`<artifactId>${artifact}</artifactId>`)) {
      addError(`backend/pom.xml: 缺少依赖 ${artifact}`);
    }
  }
}

let projectAppId = '';
const projectConfigPath = path.join(root, 'project.config.json');
if (fs.existsSync(projectConfigPath)) {
  const config = parseJson(projectConfigPath);
  projectAppId = config?.appid || '';
  if (isPlaceholder(projectAppId)) placeholder('project.config.json: appid 仍是占位值');
  else if (!/^wx[a-f0-9]{16}$/i.test(projectAppId)) addError('project.config.json: appid 格式无效');
  if (config?.miniprogramRoot !== 'miniprogram/') {
    addError('project.config.json: miniprogramRoot 必须为 "miniprogram/"');
  }
}

let clientConfig = null;
const clientEnvPath = path.join(root, 'miniprogram/config/env.js');
if (fs.existsSync(clientEnvPath)) {
  try {
    delete require.cache[require.resolve(clientEnvPath)];
    clientConfig = require(clientEnvPath);
    const api = inspectApiBaseUrl(clientConfig.apiBaseUrl);
    if (!api.supportedProtocol) {
      addError('miniprogram/config/env.js: apiBaseUrl 只允许 http 或 https');
    }
    if (!api.originOnly) {
      addError('miniprogram/config/env.js: apiBaseUrl 必须只含 origin，不得包含路径、查询、片段或登录凭据');
    }
    if (!api.productionReady) {
      placeholder('miniprogram/config/env.js: apiBaseUrl 生产必须使用非私网、非 IP 的 HTTPS 合法域名');
    }
    if (isPlaceholder(clientConfig.subscribeTemplateId)) {
      placeholder('miniprogram/config/env.js: subscribeTemplateId 仍是占位值');
    }
    if (!/^\d+\.\d+\.\d+$/.test(clientConfig.version || '')) {
      addError('miniprogram/config/env.js: version 必须是 x.y.z');
    }
  } catch (error) {
    addError(`miniprogram/config/env.js 无法加载：${error.message}`);
  }
}

const dotenv = { ...loadDotenv(), ...process.env };
const databaseUrl = environmentValue(dotenv, ['SPRING_DATASOURCE_URL']);
if (databaseUrl) {
  if (isPlaceholder(databaseUrl)) placeholder('.env/process: SPRING_DATASOURCE_URL 仍是占位值');
} else {
  const splitDatabaseVariables = [
    ['POSTGRES_DB'],
    ['POSTGRES_USER'],
    ['POSTGRES_PASSWORD']
  ];
  const missingDatabaseVariables = splitDatabaseVariables
    .filter((names) => isPlaceholder(environmentValue(dotenv, names)))
    .map((names) => names.join('/'));
  if (missingDatabaseVariables.length > 0) {
    placeholder(`.env/process: 须配置 SPRING_DATASOURCE_URL 或完整 POSTGRES_* 数据库变量；缺少 ${missingDatabaseVariables.join(', ')}`);
  }
  const databasePort = environmentValue(dotenv, ['POSTGRES_PORT']);
  if (databasePort && (!/^\d+$/.test(databasePort)
    || Number(databasePort) < 1
    || Number(databasePort) > 65535)) {
    addError('.env/process: POSTGRES_PORT 必须是 1..65535');
  }
}
for (const name of ['WECHAT_APP_ID', 'WECHAT_APP_SECRET', 'SUBSCRIBE_TEMPLATE_ID']) {
  if (isPlaceholder(dotenv[name])) placeholder(`.env/process: ${name} 仍是占位值或未配置`);
}
if (dotenv.MINIPROGRAM_STATE !== 'formal') {
  placeholder('.env/process: MINIPROGRAM_STATE 发布必须为 formal');
}
if (!isPlaceholder(dotenv.WECHAT_APP_ID) && projectAppId && dotenv.WECHAT_APP_ID !== projectAppId) {
  addError('.env/process: WECHAT_APP_ID 与 project.config.json appid 不一致');
}
if (!isPlaceholder(dotenv.SUBSCRIBE_TEMPLATE_ID)
  && !isPlaceholder(clientConfig?.subscribeTemplateId)
  && dotenv.SUBSCRIBE_TEMPLATE_ID !== clientConfig.subscribeTemplateId) {
  addError('客户端 subscribeTemplateId 与服务端 SUBSCRIBE_TEMPLATE_ID 不一致');
}

const exampleEnv = fs.existsSync(path.join(root, '.env.example'))
  ? fs.readFileSync(path.join(root, '.env.example'), 'utf8')
  : '';
const exampleHasDatabaseUrl = /^SPRING_DATASOURCE_URL=/m.test(exampleEnv);
const exampleHasSplitDatabase = [
  ['POSTGRES_DB'],
  ['POSTGRES_USER'],
  ['POSTGRES_PASSWORD']
].every((names) => names.some((name) => new RegExp(`^${name}=`, 'm').test(exampleEnv)));
if (!exampleHasDatabaseUrl && !exampleHasSplitDatabase) {
  addError('.env.example: 须包含 SPRING_DATASOURCE_URL 或完整 POSTGRES_* 数据库变量');
}
for (const name of ['WECHAT_APP_ID', 'WECHAT_APP_SECRET', 'SUBSCRIBE_TEMPLATE_ID']) {
  if (!new RegExp(`^${name}=`, 'm').test(exampleEnv)) addError(`.env.example: 缺少 ${name}`);
}

const appSource = fs.existsSync(path.join(root, 'miniprogram/app.js'))
  ? fs.readFileSync(path.join(root, 'miniprogram/app.js'), 'utf8')
  : '';
const apiSource = fs.existsSync(path.join(root, 'miniprogram/utils/api.js'))
  ? fs.readFileSync(path.join(root, 'miniprogram/utils/api.js'), 'utf8')
  : '';
if (/wx\.cloud/.test(appSource) || /wx\.cloud/.test(apiSource)) {
  addError('小程序客户端不得继续依赖 wx.cloud');
}
if (!/Authorization/.test(apiSource) || !/wx\.request/.test(apiSource)) {
  addError('miniprogram/utils/api.js: 必须通过 Bearer token 调用自建 API');
}

const migrationPath = path.join(root, 'backend/src/main/resources/db/migration/V1__init.sql');
if (fs.existsSync(migrationPath)) {
  const migration = fs.readFileSync(migrationPath, 'utf8');
  for (const table of ['idols', 'sources', 'posts', 'users', 'sessions', 'notification_deliveries']) {
    if (!new RegExp(`CREATE TABLE ${table}\\b`, 'i').test(migration)) {
      addError(`V1__init.sql: 缺少 ${table} 表`);
    }
  }
  if (!/PRIMARY KEY \(post_id, user_id\)/i.test(migration)) {
    addError('V1__init.sql: 缺少推送幂等唯一约束');
  }
}

const composePath = path.join(root, 'compose.yaml');
if (fs.existsSync(composePath)) {
  const compose = fs.readFileSync(composePath, 'utf8');
  for (const service of ['postgres:', 'redis:', 'migrate:', 'app:', 'fetch-feeds:']) {
    if (!compose.includes(service)) addError(`compose.yaml: 缺少 ${service.slice(0, -1)} 服务`);
  }
  if (!compose.includes('127.0.0.1:${POSTGRES_PORT:-5432}:5432')) {
    addError('compose.yaml: PostgreSQL 端口必须仅绑定 127.0.0.1');
  }
  if (!compose.includes('127.0.0.1:${REDIS_PORT:-6379}:6379')) {
    addError('compose.yaml: Redis 端口必须仅绑定 127.0.0.1');
  }
  if (/command:\s*\[[^\]]*node/i.test(compose)) {
    addError('compose.yaml: 生产服务不得执行 Node.js');
  }
  for (const name of [
    'SPRING_DATASOURCE_URL',
    'SPRING_DATA_REDIS_HOST',
    'IDOLRADAR_WECHAT_APP_ID',
    'IDOLRADAR_WECHAT_APP_SECRET',
    'IDOLRADAR_SUBSCRIBE_TEMPLATE_ID',
    'IDOLRADAR_WORKER_RSS_TIMEOUT',
    'IDOLRADAR_WORKER_RSS_MAX_RESPONSE_BYTES',
    'IDOLRADAR_WORKER_NOTIFICATION_MAX_ATTEMPTS'
  ]) {
    if (!new RegExp(`^\\s+${name}:`, 'm').test(compose)) {
      addError(`compose.yaml: 缺少运行变量 ${name}`);
    }
  }
}

const dockerfilePath = path.join(root, 'Dockerfile');
if (fs.existsSync(dockerfilePath)) {
  const dockerfile = fs.readFileSync(dockerfilePath, 'utf8');
  if (/^FROM\s+node\b/im.test(dockerfile) || /\bnode\b.*server\//i.test(dockerfile)) {
    addError('Dockerfile: 生产镜像不得使用 Node.js 后端');
  }
  if (!/^USER\s+idolradar\s*$/m.test(dockerfile)) {
    addError('Dockerfile: 运行阶段必须使用非 root 用户 idolradar');
  }
}

const productionComposePath = path.join(root, 'compose.prod.yaml');
if (fs.existsSync(productionComposePath)) {
  const productionCompose = fs.readFileSync(productionComposePath, 'utf8');
  for (const requiredSecret of [
    'IDOLRADAR_IMAGE',
    'MIGRATION_DATASOURCE_PASSWORD',
    'SPRING_DATASOURCE_PASSWORD',
    'REDIS_PASSWORD',
    'WECHAT_APP_SECRET',
    'SUBSCRIBE_TEMPLATE_ID'
  ]) {
    if (!productionCompose.includes(`\${${requiredSecret}:?required}`)) {
      addError(`compose.prod.yaml: ${requiredSecret} 必须为强制注入变量`);
    }
  }
  if (!/SPRING_DATA_REDIS_SSL_ENABLED:\s*"true"/.test(productionCompose)) {
    addError('compose.prod.yaml: 托管 Redis 必须启用 TLS');
  }
  if (!/migrate:[\s\S]*?SPRING_FLYWAY_ENABLED:\s*"true"/.test(productionCompose)
    || !/app:[\s\S]*?SPRING_FLYWAY_ENABLED:\s*"false"/.test(productionCompose)) {
    addError('compose.prod.yaml: Flyway 只能由 migrate 服务执行');
  }
}

const idolSeedPath = path.join(seedDir, 'idols.seed.jsonl');
const sourceSeedPath = path.join(seedDir, 'sources.seed.jsonl');
if (!fs.existsSync(idolSeedPath)) addError(`缺少发布种子：${idolSeedPath}`);
if (!fs.existsSync(sourceSeedPath)) addError(`缺少发布种子：${sourceSeedPath}`);
const idols = fs.existsSync(idolSeedPath) ? parseJsonLines(idolSeedPath) : [];
const sources = fs.existsSync(sourceSeedPath) ? parseJsonLines(sourceSeedPath) : [];
const idolIds = new Set(idols.map((idol) => idol._id));
if (idolIds.size !== idols.length) addError(`${relative(idolSeedPath)}: _id 必须唯一`);
if (new Set(sources.map((source) => source._id)).size !== sources.length) {
  addError(`${relative(sourceSeedPath)}: _id 必须唯一`);
}
for (const idol of idols) {
  if (!idol._id || !idol.name || typeof idol.enabled !== 'boolean') {
    addError(`${relative(idolSeedPath)}: idol 必须包含 _id/name/enabled`);
  }
}
for (const source of sources) {
  if (!source._id || !source.idolId || !source.rssUrl || !source.channel
    || typeof source.enabled !== 'boolean') {
    addError(`${relative(sourceSeedPath)}: source 必须包含 _id/idolId/rssUrl/channel/enabled`);
  }
  if (source.idolId && !idolIds.has(source.idolId)) {
    addError(`${relative(sourceSeedPath)}: ${source._id || '(unknown)'} 的 idolId 无对应 idol`);
  }
  try {
    const url = new URL(source.rssUrl);
    if (url.protocol !== 'https:' || url.username || url.password || isPrivateApiHostname(url.hostname)) {
      throw new Error('unsafe RSS URL');
    }
  } catch {
    const message = `${relative(sourceSeedPath)}: ${source._id || '(unknown)'} 的 rssUrl 不安全或无效`;
    // example.invalid 只允许通过本地占位校验；正式发布时 placeholder() 仍会转成错误。
    if (isPlaceholder(source.rssUrl)) placeholder(message);
    else addError(message);
  }
}
for (const seedPath of [idolSeedPath, sourceSeedPath]) {
  if (fs.existsSync(seedPath)
    && /example\.invalid|（示例）|上线前请替换/.test(fs.readFileSync(seedPath, 'utf8'))) {
    placeholder(`${relative(seedPath)}: 仍含安全示例数据，上线前必须替换`);
  }
}

for (const warning of warnings) console.warn(`WARN ${warning}`);
for (const error of errors) console.error(`ERROR ${error}`);
if (errors.length > 0) {
  console.error(`\n发布校验失败：${errors.length} 项错误，${warnings.length} 项警告`);
  process.exitCode = 1;
} else {
  console.log(`发布校验通过：0 项错误，${warnings.length} 项警告`);
}

module.exports = { inspectApiBaseUrl, isPrivateApiHostname };
