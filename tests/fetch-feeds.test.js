'use strict';

process.env.TZ = 'Asia/Shanghai';

const assert = require('node:assert/strict');
const test = require('node:test');
const { loadContract } = require('./helpers/contract');

let rss;
let rssDependencyError;
let security;
let posts;

test.before(() => {
  security = loadContract('cloudfunctions/fetchFeeds/lib/security.js', [
    'validateFeedUrl',
    'isPrivateHostname',
    'isPrivateIp',
    'resolveAndValidateHost'
  ]);
  posts = loadContract('cloudfunctions/fetchFeeds/lib/posts.js', [
    'selectLatestPostsByIdol'
  ]);
  try {
    rss = loadContract('cloudfunctions/fetchFeeds/lib/rss.js', [
      'parseFeed',
      'normalizeEntry',
      'canonicalizeLink',
      'postIdForLink',
      'stripHtml'
    ]);
  } catch (error) {
    if (error?.code === 'MODULE_NOT_FOUND' && /fast-xml-parser/.test(error.message)) {
      rssDependencyError = error;
    } else {
      throw error;
    }
  }
});

function requireRssDependency(context) {
  if (!rssDependencyError) return true;
  context.skip('先执行 npm --prefix cloudfunctions/fetchFeeds install --omit=dev');
  return false;
}

test('parseFeed normalizes an RSS item', async (context) => {
  if (!requireRssDependency(context)) return;
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
    <rss version="2.0">
      <channel>
        <title>示例源</title>
        <item>
          <title><![CDATA[ 新动态 ]]></title>
          <description><![CDATA[<p>动态摘要</p>]]></description>
          <link>https://example.com/posts/1#comments</link>
          <pubDate>Wed, 22 Jul 2026 04:00:00 GMT</pubDate>
        </item>
      </channel>
    </rss>`;

  const result = await rss.parseFeed(xml, new Date('2026-07-22T05:00:00Z'));
  assert.equal(result.length, 1);
  assert.deepEqual(result[0], {
    title: '新动态',
    summary: '动态摘要',
    link: 'https://example.com/posts/1',
    publishedAt: new Date('2026-07-22T04:00:00Z')
  });
});

test('parseFeed normalizes Atom and chooses its alternate link', (context) => {
  if (!requireRssDependency(context)) return;
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
    <feed xmlns="http://www.w3.org/2005/Atom">
      <entry>
        <title>Atom 动态</title>
        <summary><![CDATA[<p>Atom 摘要</p>]]></summary>
        <link rel="self" href="https://example.com/api/posts/1" />
        <link rel="alternate" href="https://example.com/posts/1#top" />
        <updated>2026-07-22T04:00:00Z</updated>
      </entry>
    </feed>`;

  assert.deepEqual(rss.parseFeed(xml, new Date('2026-07-22T05:00:00Z')), [{
    title: 'Atom 动态',
    summary: 'Atom 摘要',
    link: 'https://example.com/posts/1',
    publishedAt: new Date('2026-07-22T04:00:00Z')
  }]);
});

test('normalizeEntry falls back summary and invalid date to fetchedAt', (context) => {
  if (!requireRssDependency(context)) return;
  const fetchedAt = new Date('2026-07-22T04:30:00Z');
  assert.deepEqual(rss.normalizeEntry({
    title: '只有标题',
    link: 'https://example.com/posts/2#top',
    publishedAt: 'invalid'
  }, { fetchedAt }), {
    title: '只有标题',
    summary: '只有标题',
    link: 'https://example.com/posts/2',
    publishedAt: fetchedAt
  });
});

test('parseFeed rejects malformed or entry-less XML', async (context) => {
  if (!requireRssDependency(context)) return;
  await assert.rejects(
    Promise.resolve().then(() => rss.parseFeed('<rss><channel><item>', new Date())),
    Error
  );
  await assert.rejects(
    Promise.resolve().then(() => rss.parseFeed('<rss><channel /></rss>', new Date())),
    Error
  );
  assert.throws(
    () => rss.parseFeed('<!DOCTYPE rss [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><rss><channel /></rss>'),
    (error) => error.code === 'UNSAFE_XML'
  );
});

test('link canonicalization strips fragments and produces stable dedupe IDs', (context) => {
  if (!requireRssDependency(context)) return;
  const canonical = rss.canonicalizeLink(' https://example.com/a?x=1#section ');
  assert.equal(canonical, 'https://example.com/a?x=1');
  assert.equal(rss.stripHtml('<p>动态摘要</p>'), '动态摘要');

  const first = rss.postIdForLink('https://example.com/a?x=1#one');
  const second = rss.postIdForLink('https://example.com/a?x=1#two');
  assert.match(first, /^[a-f0-9]{64}$/);
  assert.equal(first, second);
  assert.throws(() => rss.canonicalizeLink('file:///etc/passwd'), Error);
});

test('private host and IP detection covers loopback and RFC1918 ranges', () => {
  assert.equal(security.isPrivateHostname('localhost'), true);
  assert.equal(security.isPrivateHostname('api.localhost'), true);
  assert.equal(security.isPrivateHostname('example.com'), false);

  for (const address of ['127.0.0.1', '10.0.0.1', '172.16.0.1', '192.168.1.1', '169.254.169.254', '::1', 'fc00::1', 'fe80::1']) {
    assert.equal(security.isPrivateIp(address), true, `${address} 必须视为私有/保留地址`);
  }
  assert.equal(security.isPrivateIp('8.8.8.8'), false);
  assert.equal(security.isPrivateIp('2606:4700:4700::1111'), false);
});

test('validateFeedUrl allows public HTTP(S) URLs and blocks unsafe literals', () => {
  assert.equal(security.validateFeedUrl('https://example.com/feed.xml').href, 'https://example.com/feed.xml');
  assert.equal(security.validateFeedUrl('http://example.com/feed.xml').protocol, 'http:');

  for (const url of [
    'ftp://example.com/feed.xml',
    'https://user:password@example.com/feed.xml',
    'http://localhost/feed.xml',
    'http://127.0.0.1/feed.xml',
    'http://169.254.169.254/latest/meta-data'
  ]) {
    assert.throws(() => security.validateFeedUrl(url), Error, `${url} 必须被拒绝`);
  }
});

test('DNS validation rejects mixed public/private answers before address pinning', async () => {
  await assert.rejects(
    () => security.resolveAndValidateHost('rss.example.com', async () => [
      { address: '8.8.8.8', family: 4 },
      { address: '10.0.0.7', family: 4 }
    ]),
    (error) => error.code === 'UNSAFE_FEED_URL'
  );
  assert.deepEqual(
    await security.resolveAndValidateHost('rss.example.com', async () => [
      { address: '8.8.8.8', family: 4 },
      { address: '2606:4700:4700::1111', family: 6 }
    ]),
    [
      { address: '8.8.8.8', family: 4 },
      { address: '2606:4700:4700::1111', family: 6 }
    ]
  );
});

test('selectLatestPostsByIdol returns one deterministic latest post per idol', () => {
  const result = posts.selectLatestPostsByIdol([
    { _id: 'a', idolId: 'idol-1', publishedAt: '2026-07-22T03:00:00Z' },
    { _id: 'b', idolId: 'idol-1', publishedAt: '2026-07-22T04:00:00Z' },
    { _id: 'c', idolId: 'idol-2', publishedAt: '2026-07-22T04:00:00Z' },
    { _id: 'd', idolId: 'idol-2', publishedAt: '2026-07-22T04:00:00Z' }
  ]);

  assert.ok(result instanceof Map);
  assert.equal(result.size, 2);
  assert.equal(result.get('idol-1')._id, 'b');
  assert.equal(result.get('idol-2')._id, 'd');
});
