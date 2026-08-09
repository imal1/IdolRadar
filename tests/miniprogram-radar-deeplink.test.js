'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');

const radarPagePath = require.resolve('../miniprogram/pages/radar/index');

function loadRadarPage() {
  const originalPage = global.Page;
  let definition;
  global.Page = function (options) {
    definition = options;
  };

  delete require.cache[radarPagePath];
  require(radarPagePath);
  global.Page = originalPage;
  return definition;
}

test('push deep link scrolls to the matching previous post', () => {
  const originalWx = global.wx;
  const originalSetTimeout = global.setTimeout;
  const scrolls = [];
  global.wx = {
    pageScrollTo: function (options) {
      scrolls.push(options);
    }
  };
  global.setTimeout = function (callback) {
    callback();
    return 1;
  };

  try {
    const page = loadRadarPage();
    page.scrollToDeepLink.call({
      data: {
        deepLinkPostId: 'post-2',
        latestPost: { id: 'post-1' },
        allPosts: [{ id: 'post-1' }, { id: 'post-2' }]
      }
    });

    assert.deepEqual(scrolls, [{ selector: '#post-post-2', duration: 300 }]);
  } finally {
    global.wx = originalWx;
    global.setTimeout = originalSetTimeout;
  }
});
