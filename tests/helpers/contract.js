'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '../..');

function loadContract(relativePath, expectedExports) {
  const absolute = path.join(root, relativePath);
  assert.ok(fs.existsSync(absolute), `约定模块不存在：${relativePath}`);

  const loaded = require(absolute);
  for (const exportName of expectedExports) {
    assert.equal(
      typeof loaded[exportName],
      'function',
      `${relativePath} 必须导出函数 ${exportName}`
    );
  }
  return loaded;
}

module.exports = { loadContract };
