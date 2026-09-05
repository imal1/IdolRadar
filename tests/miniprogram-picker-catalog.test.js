'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const apiPath = require.resolve('../miniprogram/utils/api');
const pagePath = require.resolve('../miniprogram/pages/picker/index');

test('partial verified rollout keeps unverified idols out of search and preserves the current guard', async (t) => {
  // 本轮仅虞书欣、白鹿真实抓取通过；赵露思保留停用候选，不进入 listIdols 公开名单。
  const seed = fs.readFileSync(path.join(__dirname, '..', 'database', 'idols.seed.jsonl'), 'utf8')
    .split('\n').filter((line) => line.trim()).map((line) => JSON.parse(line));
  const catalog = { idols: seed.filter((idol) => idol.enabled), currentIdolId: 'idol_wang_yibo' };
  assert.equal(catalog.idols.some((idol) => idol._id === 'idol_zhao_lusi'), false);

  const originalApiModule = require.cache[apiPath];
  const originalPageModule = require.cache[pagePath];
  const originalPage = global.Page;
  const originalGetApp = global.getApp;
  t.after(() => {
    if (originalApiModule) require.cache[apiPath] = originalApiModule;
    else delete require.cache[apiPath];
    if (originalPageModule) require.cache[pagePath] = originalPageModule;
    else delete require.cache[pagePath];
    global.Page = originalPage;
    global.getApp = originalGetApp;
  });

  // 沿用现有 Node Page 测试：只替换公开 API 与 bootstrap，不改搜索方法，也不启动 UI 或网络。
  require.cache[apiPath] = {
    id: apiPath, filename: apiPath, loaded: true,
    exports: { callUser(action) {
      assert.equal(action, 'listIdols');
      return Promise.resolve(catalog);
    } }
  };
  let definition;
  global.Page = (options) => { definition = options; };
  global.getApp = () => ({ ensureBootstrap: () => Promise.resolve({ user: { idolId: 'idol_wang_yibo' } }) });
  delete require.cache[pagePath];
  require(pagePath);
  const page = Object.assign({}, definition, {
    data: Object.assign({}, definition.data),
    setData(changes) { Object.assign(page.data, changes); }
  });

  await page.loadData();
  assert.equal(page.data.errorMessage, '');
  assert.equal(page.data.currentIdolId, 'idol_wang_yibo');
  const names = () => page.data.filteredIdols.map((idol) => idol.name);
  const initialNames = names();
  assert.deepEqual(new Set(initialNames), new Set(['王一博', '虞书欣', '白鹿']));
  for (const [query, expected] of [['书欣', '虞书欣'], ['白', '白鹿']]) {
    page.search({ detail: { value: query } });
    assert.deepEqual(names(), [expected]);
  }
  page.search({ detail: { value: '不存在的守护对象' } });
  assert.deepEqual(names(), []);
  page.search({ detail: { value: '' } });
  assert.deepEqual(names(), initialNames);
  assert.equal(page.data.currentIdolId, 'idol_wang_yibo');
});
