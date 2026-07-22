#!/usr/bin/env node

'use strict';

const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');

const root = path.resolve(__dirname, '..');
const roots = ['miniprogram', 'cloudfunctions', 'scripts', 'tests'];
const files = [];

function collect(directory) {
  if (!fs.existsSync(directory)) return;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    if (entry.name === 'node_modules') continue;
    const filePath = path.join(directory, entry.name);
    if (entry.isDirectory()) collect(filePath);
    else if (entry.isFile() && entry.name.endsWith('.js')) files.push(filePath);
  }
}

for (const directory of roots) collect(path.join(root, directory));

const failures = [];
for (const filePath of files.sort()) {
  const result = spawnSync(process.execPath, ['--check', filePath], { encoding: 'utf8' });
  if (result.status !== 0) {
    failures.push(`${path.relative(root, filePath)}\n${(result.stderr || result.stdout).trim()}`);
  }
}

if (failures.length > 0) {
  console.error(failures.join('\n\n'));
  console.error(`\n语法检查失败：${failures.length}/${files.length}`);
  process.exitCode = 1;
} else {
  console.log(`语法检查通过：${files.length} 个 JavaScript 文件`);
}
