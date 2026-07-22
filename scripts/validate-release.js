#!/usr/bin/env node

'use strict';

const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const allowPlaceholders = process.argv.includes('--allow-placeholders');
const seedDirFlag = process.argv.indexOf('--seed-dir');
if (seedDirFlag >= 0 && !process.argv[seedDirFlag + 1]) {
  console.error('ERROR --seed-dir 后必须提供目录路径');
  process.exit(1);
}
const seedDir = seedDirFlag >= 0 && process.argv[seedDirFlag + 1]
  ? path.resolve(process.argv[seedDirFlag + 1])
  : path.join(root, 'database');
const errors = [];
const warnings = [];
const dotenv = loadDotenv();
let cloudbaseEnvId = null;
let serverTemplateId = null;
const fetchTriggerContract = {
  name: 'fetchFeedsEvery30Minutes',
  type: 'timer',
  config: '0 */30 * * * * *'
};

function relative(filePath) {
  return path.relative(root, filePath) || '.';
}

function addError(message) {
  errors.push(message);
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
        || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }
      values[match[1]] = value;
    }
  }
  return values;
}

function resolveDynamic(value) {
  if (typeof value !== 'string') return value;
  return value.replace(/\{\{env\.([A-Za-z_][A-Za-z0-9_]*)\}\}/g, (token, name) => (
    process.env[name] || dotenv[name] || token
  ));
}

function walkJson(directory) {
  if (!fs.existsSync(directory)) return;

  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (entry.name === 'node_modules' || entry.name === '.git' || entry.name === 'graphify-out') {
      continue;
    }

    const filePath = path.join(directory, entry.name);
    if (entry.isDirectory()) walkJson(filePath);
    else if (entry.isFile() && entry.name.endsWith('.json')) parseJson(filePath);
  }
}

function placeholder(message) {
  if (allowPlaceholders) warnings.push(message);
  else addError(message);
}

function isPlaceholder(value) {
  if (typeof value !== 'string') return true;
  const normalized = resolveDynamic(value).trim().toLowerCase();
  return !normalized
    || normalized === 'touristappid'
    || normalized.includes('example.invalid')
    || normalized.includes('your_')
    || normalized.includes('replace_me')
    || normalized.includes('replace-with')
    || normalized.includes('replace_with')
    || normalized.includes('请替换')
    || normalized.includes('{{env.')
    || /^cloud1-placeholder/.test(normalized);
}

const requiredFiles = [
  'package.json',
  'project.config.json',
  'cloudbaserc.json',
  'miniprogram/app.js',
  'miniprogram/app.json',
  'miniprogram/app.wxss',
  'miniprogram/sitemap.json',
  'miniprogram/config/env.js',
  'database/idols.seed.jsonl',
  'database/sources.seed.jsonl',
  'database/indexes.json',
  'database/security-rules.json',
  'database/function-security-rules.json',
  'cloudfunctions/user/index.js',
  'cloudfunctions/user/package.json',
  'cloudfunctions/user/package-lock.json',
  'cloudfunctions/sendNotify/index.js',
  'cloudfunctions/sendNotify/package.json',
  'cloudfunctions/sendNotify/package-lock.json',
  'cloudfunctions/sendNotify/config.json',
  'cloudfunctions/fetchFeeds/index.js',
  'cloudfunctions/fetchFeeds/package.json',
  'cloudfunctions/fetchFeeds/package-lock.json',
  'cloudfunctions/fetchFeeds/config.json'
];

for (const filePath of requiredFiles) requireFile(filePath);
walkJson(root);

const projectConfigPath = path.join(root, 'project.config.json');
if (fs.existsSync(projectConfigPath)) {
  const config = parseJson(projectConfigPath);
  if (config) {
    if (isPlaceholder(config.appid)) placeholder('project.config.json: appid 仍是占位值');
    else if (!/^wx[a-f0-9]{16}$/i.test(config.appid)) {
      addError('project.config.json: appid 格式无效');
    }
    if (config.miniprogramRoot !== 'miniprogram/') {
      addError('project.config.json: miniprogramRoot 必须为 "miniprogram/"');
    }
    if (config.cloudfunctionRoot !== 'cloudfunctions/') {
      addError('project.config.json: cloudfunctionRoot 必须为 "cloudfunctions/"');
    }
  }
}

const cloudbasePath = path.join(root, 'cloudbaserc.json');
if (fs.existsSync(cloudbasePath)) {
  const config = parseJson(cloudbasePath);
  if (config) {
    if (config.functionRoot !== './cloudfunctions') {
      addError('cloudbaserc.json: functionRoot 必须为 "./cloudfunctions"');
    }
    const envId = config.envId || config.env || config.environments?.[0]?.envId;
    cloudbaseEnvId = resolveDynamic(envId);
    if (isPlaceholder(envId)) placeholder('cloudbaserc.json: 云环境 ID 仍是占位值');

    const functions = Array.isArray(config.functions) ? config.functions : [];
    const functionsByName = new Map(functions.map((entry) => [entry?.name, entry]));
    const functionContracts = {
      user: { timeout: 15, memorySize: 256 },
      sendNotify: { timeout: 120, memorySize: 256 },
      fetchFeeds: { timeout: 180, memorySize: 512 }
    };
    for (const [functionName, contract] of Object.entries(functionContracts)) {
      const entry = functionsByName.get(functionName);
      if (!entry) {
        addError(`cloudbaserc.json: 缺少 ${functionName} 云函数配置`);
        continue;
      }
      if (entry.runtime !== 'Nodejs20.19') addError(`cloudbaserc.json: ${functionName} runtime 必须为 Nodejs20.19`);
      if (entry.handler !== 'index.main') addError(`cloudbaserc.json: ${functionName} handler 必须为 index.main`);
      if (entry.installDependency !== true) addError(`cloudbaserc.json: ${functionName} 必须启用 installDependency`);
      if (entry.timeout !== contract.timeout) addError(`cloudbaserc.json: ${functionName} timeout 必须为 ${contract.timeout}`);
      if (entry.memorySize !== contract.memorySize) addError(`cloudbaserc.json: ${functionName} memorySize 必须为 ${contract.memorySize}`);
    }

    const templateId = functionsByName.get('sendNotify')?.envVariables?.SUBSCRIBE_TEMPLATE_ID;
    serverTemplateId = resolveDynamic(templateId);
    if (isPlaceholder(templateId)) {
      placeholder('cloudbaserc.json: SUBSCRIBE_TEMPLATE_ID 仍是未解析占位值');
    }

    const triggers = functionsByName.get('fetchFeeds')?.triggers;
    const timerTriggers = Array.isArray(triggers)
      ? triggers.filter((trigger) => trigger && trigger.type === 'timer')
      : [];
    const trigger = timerTriggers[0];
    if (timerTriggers.length !== 1
      || trigger.name !== fetchTriggerContract.name
      || trigger.config !== fetchTriggerContract.config) {
      addError('cloudbaserc.json: fetchFeeds timer 名称/周期必须与发布契约一致');
    }
  }
}

const envPath = path.join(root, 'miniprogram/config/env.js');
if (fs.existsSync(envPath)) {
  try {
    delete require.cache[require.resolve(envPath)];
    const env = require(envPath);
    if (isPlaceholder(env.cloudEnvId)) placeholder('miniprogram/config/env.js: cloudEnvId 仍是占位值');
    if (isPlaceholder(env.subscribeTemplateId)) {
      placeholder('miniprogram/config/env.js: subscribeTemplateId 仍是占位值');
    }
    if (typeof env.version !== 'string' || !/^\d+\.\d+\.\d+$/.test(env.version)) {
      addError('miniprogram/config/env.js: version 必须是 x.y.z');
    }
    if (!isPlaceholder(env.cloudEnvId) && !isPlaceholder(cloudbaseEnvId)
      && env.cloudEnvId !== cloudbaseEnvId) {
      addError('客户端 cloudEnvId 与 cloudbaserc.json 云环境 ID 不一致');
    }
    if (!isPlaceholder(env.subscribeTemplateId) && !isPlaceholder(serverTemplateId)
      && env.subscribeTemplateId !== serverTemplateId) {
      addError('客户端 subscribeTemplateId 与服务端 SUBSCRIBE_TEMPLATE_ID 不一致');
    }
  } catch (error) {
    addError(`miniprogram/config/env.js 无法加载：${error.message}`);
  }
}

const appJsonPath = path.join(root, 'miniprogram/app.json');
if (fs.existsSync(appJsonPath)) {
  const app = parseJson(appJsonPath);
  if (app) {
    const expectedPages = ['pages/radar/index', 'pages/picker/index', 'pages/me/index'];
    if (!Array.isArray(app.pages) || JSON.stringify(app.pages) !== JSON.stringify(expectedPages)) {
      addError(`miniprogram/app.json: pages 必须依次为 ${expectedPages.join(', ')}`);
    } else {
      for (const route of app.pages) {
        for (const extension of ['js', 'json', 'wxml', 'wxss']) {
          requireFile(`miniprogram/${route}.${extension}`);
        }
      }
    }
  }
}

const triggerPath = path.join(root, 'cloudfunctions/fetchFeeds/config.json');
if (fs.existsSync(triggerPath)) {
  const config = parseJson(triggerPath);
  if (config) {
    const triggers = Array.isArray(config.triggers) ? config.triggers : [];
    const trigger = triggers[0];
    if (triggers.length !== 1
      || trigger?.name !== fetchTriggerContract.name
      || trigger?.type !== fetchTriggerContract.type
      || trigger?.config !== fetchTriggerContract.config) {
      addError('cloudfunctions/fetchFeeds/config.json: timer 必须与 cloudbaserc.json 唯一契约一致');
    }
  }
}

const notifyConfigPath = path.join(root, 'cloudfunctions/sendNotify/config.json');
if (fs.existsSync(notifyConfigPath)) {
  const config = parseJson(notifyConfigPath);
  const openapi = config?.permissions?.openapi;
  if (!Array.isArray(openapi) || !openapi.includes('subscribeMessage.send')) {
    addError('cloudfunctions/sendNotify/config.json: 缺少 subscribeMessage.send 权限');
  }
}

const idolSeedPath = path.join(seedDir, 'idols.seed.jsonl');
const sourceSeedPath = path.join(seedDir, 'sources.seed.jsonl');
if (!fs.existsSync(idolSeedPath)) addError(`缺少发布种子：${idolSeedPath}`);
if (!fs.existsSync(sourceSeedPath)) addError(`缺少发布种子：${sourceSeedPath}`);
const idols = fs.existsSync(idolSeedPath) ? parseJsonLines(idolSeedPath) : [];
const sources = fs.existsSync(sourceSeedPath) ? parseJsonLines(sourceSeedPath) : [];
const idolIds = new Set(idols.map((idol) => idol._id));
let validateFeedUrl = null;
try {
  ({ validateFeedUrl } = require(path.join(root, 'cloudfunctions/fetchFeeds/lib/security.js')));
} catch (error) {
  addError(`无法加载 RSS URL 安全校验：${error.message}`);
}
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
    const url = validateFeedUrl ? validateFeedUrl(source.rssUrl) : new URL(source.rssUrl);
    if (url.protocol !== 'https:') addError(`${relative(sourceSeedPath)}: RSS 必须使用 https`);
  } catch {
    addError(`${relative(sourceSeedPath)}: ${source._id || '(unknown)'} 的 rssUrl 不安全或无效`);
  }
}

for (const seedPath of [idolSeedPath, sourceSeedPath]) {
  if (!fs.existsSync(seedPath)) continue;
  if (/example\.invalid|（示例）|上线前请替换/.test(fs.readFileSync(seedPath, 'utf8'))) {
    placeholder(`${relative(seedPath)}: 仍含安全示例数据，上线前必须替换`);
  }
}

const databaseRulesPath = path.join(root, 'database/security-rules.json');
if (fs.existsSync(databaseRulesPath)) {
  const rules = parseJson(databaseRulesPath);
  for (const collection of ['idols', 'sources', 'posts', 'users']) {
    if (rules?.[collection]?.read !== false || rules?.[collection]?.write !== false) {
      addError(`database/security-rules.json: ${collection} 必须禁止客户端读写`);
    }
  }
}

const functionRulesPath = path.join(root, 'database/function-security-rules.json');
if (fs.existsSync(functionRulesPath)) {
  const rules = parseJson(functionRulesPath);
  if (rules?.['*']?.invoke !== false) {
    addError('database/function-security-rules.json: 必须用 * 默认禁止客户端调用');
  }
  if (rules?.user?.invoke !== "auth.loginType != 'ANONYMOUS' && auth != null") {
    addError('database/function-security-rules.json: user 必须拒绝匿名身份，仅允许已认证客户端调用');
  }
  for (const functionName of ['fetchFeeds', 'sendNotify']) {
    if (rules?.[functionName]?.invoke !== false) {
      addError(`database/function-security-rules.json: ${functionName} 必须禁止客户端调用`);
    }
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
