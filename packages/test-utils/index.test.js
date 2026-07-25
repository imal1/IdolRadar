import assert from 'node:assert/strict';
import { test } from 'node:test';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

import { requireEnvironment, resolveExistingPath } from './index.js';

const here = dirname(fileURLToPath(import.meta.url));

test('resolveExistingPath returns an existing absolute path', () => {
  assert.equal(resolveExistingPath(here, 'package.json'), resolve(here, 'package.json'));
});

test('requireEnvironment rejects missing values and trims configured values', () => {
  assert.throws(() => requireEnvironment('MISSING', {}), /缺少环境变量 MISSING/);
  assert.equal(requireEnvironment('VALUE', { VALUE: ' configured ' }), 'configured');
});
