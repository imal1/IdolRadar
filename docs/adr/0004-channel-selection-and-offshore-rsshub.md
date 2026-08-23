---
status: accepted
---

# 渠道按网络可达性分区，海外渠道走海外 RSSHub 实例

第二渠道的选型受限于两件事：RSSHub route 的抓取成本（是否需要 Cookie、是否需要 Playwright、反爬强度），以及 RSSHub 实例所在网络能否访问目标站点。这两件事把候选平台切成了三类，不是一张按热度排序的清单。

## 分区部署

海外平台（X、Threads、YouTube）的 route 在国内服务器上不可达，靠给现有 RSSHub 加代理来解决会把出网策略混进抓取逻辑，且代理一断整个实例的抓取轮次都受影响。因此海外渠道由部署在海外服务器上的独立 RSSHub 实例承担（#60），国内渠道（微博、B 站）仍由现有实例承担。两个实例互不感知，后端只在 `idr_source.rss_url` 里记录各自的入口地址——Worker 逐 source 抓取且逐 source 捕获异常，一个实例整体不可用时不会影响另一个实例的 source。

接第一个海外渠道（X）的目的不只是补内容，更是验证「海外实例 → 国内后端」这条链路本身可行：跨境的 RSS 拉取延迟、失败率和 `FeedUrlGuard` 对海外域名的放行规则，都只能靠真实渠道跑出来。因此 X 的覆盖率低（首批候选榜里仅个别 idol 有账号）不构成否决理由，它的价值在链路验证。

## 候选平台分档

**已选**：微博（现有）、B 站（`/bilibili/user/dynamic/:uid`，Cookie 可选、无需 Playwright、无反爬标记，内容结构与微博同构）、X（`/twitter/user/:id`，需 `TWITTER_AUTH_TOKEN` 与 `TWITTER_CONSUMER_KEY/SECRET`，走海外实例）。

**待技术攻克**：抖音（`requirePuppeteer: true` + `antiCrawler: true`）、小红书（同上，另需 `XIAOHONGSHU_COOKIE` 与代理）、TikTok（同抖音）。这三个都要求 RSSHub 容器内置 Chromium，镜像体积与内存占用显著上升，且风控强度决定了 Cookie 需要人工续期。三者都不属于「做不了」，属于「先把有人值守的成本算清楚再做」，在此之前不进 seed（#61）。

**下一批候选**：Threads（`/threads/:user`，无需任何凭据，自行取 LSD token）——它与 Instagram 共用账号体系但内容以文字为主，因此可以在不接 Instagram 的前提下拿到同一批海外账号的动态；Instagram 本身以图片为主，当前 RSS 与小程序动态流都无法体面呈现，不接。YouTube（`/youtube/channel/:id`，需 `YOUTUBE_KEY`，免费配额充足、无反爬）内容稳定但更新频率低，适合作补充而非主渠道。

**已排除**：网易云音乐、QQ 音乐（RSSHub 无对应 route）；Spotify（`/spotify/artist/:id` 仅在发专辑时更新，按 `docs/idol-selection-criteria.md` 的更新频率斩杀线不合格）；豆瓣（idol 基本不使用）。大麦（`/damai/activity/...`）按关键词而非账号组织，属于「行程」类而不是「动态」类，接入需要另一套数据模型，不在本轮范围。

## 客户端影响

`idr_source.channel` 与 `idr_post.channel` 自 V5 起已贯通到小程序，`miniprogram/pages/radar/index.js` 的 `channelClass()` 已为 B 站映射到 `channel-blue`。新增渠道只要在 seed 里写对 `channel` 文案即可获得可辨识标签；只有当渠道文案落不进现有映射时才需要补一个颜色分支。
