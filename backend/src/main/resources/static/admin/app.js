"use strict";

// 身份功能连接真实 API；其余业务页随对应 Issue 逐步替换演示数据。

const icons = {
  dashboard: '<rect x="3" y="3" width="7" height="7" rx="2"/><rect x="14" y="3" width="7" height="7" rx="2"/><rect x="3" y="14" width="7" height="7" rx="2"/><rect x="14" y="14" width="7" height="7" rx="2"/>',
  idols: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="m19 8 1 2 2 .5-1.5 1.6.3 2.2-1.8-.9-1.8.9.3-2.2L16 10.5l2-.5z"/>',
  sources: '<path d="M5 11a8 8 0 0 1 8 8"/><path d="M5 5a14 14 0 0 1 14 14"/><circle cx="5" cy="19" r="1"/>',
  deliveries: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9"/><path d="M10 21h4"/>',
  requests: '<path d="M9 11l3 3L22 4"/><path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11"/>',
  audit: '<path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><path d="M14 2v6h6M8 13h8M8 17h6"/>',
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
  calendar: '<rect x="3" y="5" width="18" height="16" rx="2"/><path d="M16 3v4M8 3v4M3 10h18"/>',
  bell: '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4"/>',
  menu: '<path d="M4 6h16M4 12h16M4 18h16"/>',
  eye: '<path d="M2 12s3.5-6 10-6 10 6 10 6-3.5 6-10 6S2 12 2 12"/><circle cx="12" cy="12" r="2.5"/>',
  eyeOff: '<path d="m3 3 18 18M10.7 10.7a2 2 0 0 0 2.6 2.6M9.9 5.2A11 11 0 0 1 12 5c6.5 0 10 7 10 7a15 15 0 0 1-2 2.8M6.2 6.2C3.5 8 2 12 2 12s3.5 7 10 7c1.2 0 2.3-.2 3.3-.6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  users: '<path d="M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M16 3.1a4 4 0 0 1 0 7.8M22 21v-2a4 4 0 0 0-3-3.9"/><circle cx="9" cy="7" r="4"/>',
  trend: '<path d="m3 17 6-6 4 4 8-9"/><path d="M15 6h6v6"/>',
  send: '<path d="m22 2-7 20-4-9-9-4zM22 2 11 13"/>',
  refresh: '<path d="M20 6v6h-6M4 18v-6h6"/><path d="M18.5 9A7 7 0 0 0 6 6L4 8M5.5 15A7 7 0 0 0 18 18l2-2"/>',
  edit: '<path d="M12 20h9M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4z"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  logout: '<path d="M10 17l5-5-5-5M15 12H3M21 19V5a2 2 0 0 0-2-2h-6"/>',
  profile: '<circle cx="12" cy="8" r="4"/><path d="M4 21a8 8 0 0 1 16 0"/>',
};

function safe(value) {
  return String(value ?? "").replace(/[&<>'"]/g, (char) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[char]);
}

function icon(name) {
  return `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${icons[name] || icons.dashboard}</svg>`;
}

const pageMeta = {
  dashboard: { label: "核心指标", icon: "dashboard" },
  idols: { label: "idol 管理", icon: "idols" },
  sources: { label: "动态源", icon: "sources" },
  deliveries: { label: "推送投递", icon: "deliveries" },
  requests: { label: "申请审核", icon: "requests", badge: 3 },
  audit: { label: "审计日志", icon: "audit" },
};

const state = {
  page: "dashboard",
  deliveryFilter: "all",
  requestFilter: "pending",
  sourceStatus: "all",
  idolStatus: "all",
  auditResult: "all",
  idols: [
    { id: 1, name: "王一博", initial: "博", bio: "演员、歌手、职业赛车手", status: "enabled", sources: 3, posts: 1286, guards: 8421, version: 6, updatedAt: "今天 10:48" },
    { id: 2, name: "赵露思", initial: "露", bio: "演员", status: "enabled", sources: 2, posts: 938, guards: 6280, version: 4, updatedAt: "昨天 18:20" },
    { id: 3, name: "檀健次", initial: "檀", bio: "演员、歌手", status: "enabled", sources: 2, posts: 764, guards: 5112, version: 3, updatedAt: "8月7日 16:42" },
    { id: 4, name: "刘宇宁", initial: "宁", bio: "歌手、演员", status: "disabled", sources: 1, posts: 486, guards: 3210, version: 2, updatedAt: "8月6日 09:31" },
    { id: 5, name: "白鹿", initial: "鹿", bio: "演员", status: "enabled", sources: 2, posts: 621, guards: 4627, version: 3, updatedAt: "8月5日 20:16" },
  ],
  sources: [
    { id: 11, idol: "王一博", name: "王一博 · 微博", channel: "微博", url: "https://rss.example.com/weibo/user/5492443184", status: "healthy", enabled: true, lastFetch: "1 分钟前", lastSuccess: "1 分钟前", parsed: 20, added: 1, failures: 0 },
    { id: 12, idol: "王一博", name: "王一博工作室 · 微博", channel: "微博", url: "https://rss.example.com/weibo/user/6525010965", status: "healthy", enabled: true, lastFetch: "2 分钟前", lastSuccess: "2 分钟前", parsed: 20, added: 0, failures: 0 },
    { id: 13, idol: "王一博", name: "王一博后援会 · 微博", channel: "微博", url: "https://rss.example.com/weibo/user/guard-club", status: "failed", enabled: true, lastFetch: "8 分钟前", lastSuccess: "2 小时前", parsed: 0, added: 0, failures: 3 },
    { id: 14, idol: "赵露思", name: "赵露思 · 微博", channel: "微博", url: "https://rss.example.com/weibo/user/rosy-zhao", status: "healthy", enabled: true, lastFetch: "6 分钟前", lastSuccess: "6 分钟前", parsed: 20, added: 2, failures: 0 },
    { id: 15, idol: "檀健次", name: "檀健次工作室 · 微博", channel: "微博", url: "https://rss.example.com/weibo/user/tjc-studio", status: "waiting", enabled: true, lastFetch: "尚未抓取", lastSuccess: "—", parsed: 0, added: 0, failures: 0 },
    { id: 16, idol: "刘宇宁", name: "刘宇宁 · 微博", channel: "微博", url: "https://rss.example.com/weibo/user/lyn", status: "disabled", enabled: false, lastFetch: "2 天前", lastSuccess: "2 天前", parsed: 20, added: 1, failures: 0 },
  ],
  deliveries: [
    { id: "DLV-20260809-0186", user: "用户 #1028", idol: "王一博", post: "WB-882901", status: "success", retries: 0, sentAt: "今天 11:18:08", openedAt: "今天 11:24:36", reason: "—" },
    { id: "DLV-20260809-0185", user: "用户 #0932", idol: "赵露思", post: "WB-882896", status: "success", retries: 0, sentAt: "今天 11:16:42", openedAt: "—", reason: "—" },
    { id: "DLV-20260809-0184", user: "用户 #1124", idol: "王一博", post: "WB-882901", status: "retry", retries: 2, sentAt: "今天 11:15:10", openedAt: "—", reason: "微信接口请求超时，等待第 3 次重试" },
    { id: "DLV-20260809-0183", user: "用户 #0841", idol: "白鹿", post: "WB-882877", status: "failed", retries: 3, sentAt: "今天 11:12:25", openedAt: "—", reason: "用户拒绝接收该模板消息" },
    { id: "DLV-20260809-0182", user: "用户 #0738", idol: "檀健次", post: "WB-882865", status: "queued", retries: 0, sentAt: "—", openedAt: "—", reason: "等待投递" },
    { id: "DLV-20260809-0181", user: "用户 #0665", idol: "王一博", post: "WB-882852", status: "success", retries: 1, sentAt: "今天 11:03:16", openedAt: "今天 11:08:05", reason: "—" },
  ],
  requests: [
    { id: 201, name: "赵露思", support: 328, requester: "用户 #1182", note: "希望增加微博和工作室两个动态源。", status: "pending", createdAt: "今天 09:42" },
    { id: 202, name: "檀健次", support: 214, requester: "用户 #0971", note: "新剧宣传期动态很多，希望能及时提醒。", status: "pending", createdAt: "昨天 20:16" },
    { id: 203, name: "刘宇宁", support: 176, requester: "用户 #0744", note: "建议接入个人微博和工作室微博。", status: "pending", createdAt: "昨天 15:30" },
    { id: 204, name: "白鹿", support: 142, requester: "用户 #0615", note: "", status: "approved", createdAt: "8月7日 11:28" },
    { id: 205, name: "虞书欣", support: 96, requester: "用户 #0538", note: "同名申请已合并。", status: "rejected", createdAt: "8月6日 08:55" },
  ],
  audits: [
    { id: "AUD-00816", operator: "管理员", action: "UPDATE_SOURCE", resource: "idr_source#13", result: "success", requestId: "req-3a9f71", summary: "更新来源 RSS 地址", time: "今天 11:06:22", before: '{"rss_url":"https://old.example.com/route"}', after: '{"rss_url":"https://rss.example.com/weibo/user/guard-club"}' },
    { id: "AUD-00815", operator: "管理员", action: "TRIGGER_FETCH", resource: "idr_source#13", result: "success", requestId: "req-3a9e82", summary: "手动验证来源，不产生推送", time: "今天 10:52:18", before: "{}", after: '{"parsed":20,"inserted":0,"status":"SUCCESS"}' },
    { id: "AUD-00814", operator: "管理员", action: "REVIEW_IDOL_REQUEST", resource: "idr_idol_request#204", result: "success", requestId: "req-3a9d11", summary: "通过申请并关联正式 idol", time: "今天 10:31:44", before: '{"status":"PENDING"}', after: '{"status":"APPROVED","approved_idol_id":5}' },
    { id: "AUD-00813", operator: "管理员", action: "DISABLE_IDOL", resource: "idr_idol#4", result: "success", requestId: "req-3a9c09", summary: "停用 idol，保留历史业务数据", time: "今天 09:18:03", before: '{"enabled":true}', after: '{"enabled":false,"version":2}' },
    { id: "AUD-00812", operator: "管理员", action: "LOGIN", resource: "idr_admin_account#1", result: "success", requestId: "req-3a9b42", summary: "管理员登录", time: "今天 08:58:11", before: "{}", after: "{}" },
    { id: "AUD-00811", operator: "管理员", action: "UPDATE_IDOL", resource: "idr_idol#2", result: "failed", requestId: "req-3a9a67", summary: "版本冲突，修改未保存", time: "昨天 22:40:09", before: '{"version":3}', after: '{"expected_version":4}' },
  ],
};

const $ = (selector, root = document) => root.querySelector(selector);
const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
const number = (value) => new Intl.NumberFormat("zh-CN").format(value);
const ADMIN_TOKEN_KEY = "idolradar.admin.token";

function statusBadge(status) {
  const map = {
    enabled: ["启用", "badge--success"], disabled: ["停用", "badge--neutral"], healthy: ["正常", "badge--success"], waiting: ["待抓取", "badge--violet"],
    success: ["成功", "badge--success"], failed: ["失败", "badge--warning"], retry: ["重试中", "badge--warning"], queued: ["待发送", "badge--violet"], pending: ["待审核", "badge--warning"], approved: ["已通过", "badge--success"], rejected: ["已驳回", "badge--neutral"],
  };
  const [label, className] = map[status] || [status, "badge--neutral"];
  return `<span class="badge ${className}">${label}</span>`;
}

function sourceStatusBadge(status) {
  return status === "failed" ? '<span class="badge badge--warning">异常</span>' : statusBadge(status);
}

function pageHeading(title, description, actions = "") {
  return `<div class="page-heading"><div><h2>${title}</h2><p>${description}</p></div><div class="toolbar">${actions}</div></div>`;
}

function searchField(id, placeholder) {
  return `<label class="search-field">${icon("search")}<input id="${id}" type="search" placeholder="${placeholder}" autocomplete="off" /></label>`;
}

function pagination() {
  return '<div class="pagination"><button type="button" data-toast="已经是第一页">‹</button><button class="is-active" type="button">1</button><button type="button" data-toast="原型当前只有一页数据">2</button><button type="button" data-toast="原型当前只有一页数据">›</button></div>';
}

function dashboardPage() {
  const metric = (label, value, trend, iconName, note) => `
    <article class="metric-card">
      <div class="metric-card__top"><span class="metric-card__label">${label}</span><span class="metric-card__icon">${icon(iconName)}</span></div>
      <div class="metric-card__value">${value}</div>
      <div class="metric-card__meta"><span class="trend">↑ ${trend}</span><span>${note}</span></div>
    </article>`;
  const xValues = [55, 160, 265, 370, 475, 580, 685];
  const yValues = [163, 143, 127, 111, 80, 89, 58];
  return `
    ${pageHeading("今日概览", "从用户守护到推送回访，关注真正完成的业务闭环。", `<label class="field">${icon("calendar")}<select id="dashboard-range"><option>最近 7 天</option><option>最近 30 天</option><option>本季度</option></select></label><button class="button button--neutral" data-action="refresh-dashboard" type="button">${icon("refresh")} 刷新数据</button>`)}
    <section class="metric-grid" aria-label="核心指标">
      ${metric("新增用户", "128", "+12.6%", "users", "较上一周期")}
      ${metric("闭环转化率", "63.8%", "+5.2%", "trend", "守护至回访")}
      ${metric("推送成功率", "96.4%", "+1.8%", "send", "近 1,316 次")}
      ${metric("推送回访率", "41.2%", "+8.4%", "refresh", "打开原文")}
    </section>
    <section class="dashboard-grid">
      <article class="card">
        <div class="card__header"><div><h3>用户闭环趋势</h3><p>完成守护、完成订阅与推送回访</p></div><div class="chart-legend"><span><i class="legend-dot" style="background:#e47896"></i>完成守护</span><span><i class="legend-dot" style="background:#a98ee3"></i>完成订阅</span><span><i class="legend-dot" style="background:#6853b8"></i>推送回访</span></div></div>
        <div class="card__body">
          <svg class="chart" viewBox="0 0 720 260" role="img" aria-label="最近七天用户闭环趋势折线图">
            <defs><linearGradient id="area" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#e47896" stop-opacity=".18"/><stop offset="1" stop-color="#e47896" stop-opacity="0"/></linearGradient></defs>
            <path class="grid-line" d="M48 25H700M48 78H700M48 131H700M48 184H700M48 237H700"/>
            <text x="12" y="29">1,000</text><text x="20" y="82">750</text><text x="20" y="135">500</text><text x="20" y="188">250</text><text x="36" y="241">0</text>
            <path d="M55 163L160 143L265 127L370 111L475 80L580 89L685 58L685 237L55 237Z" fill="url(#area)"/>
            <path class="series" stroke="#e47896" d="M55 163L160 143L265 127L370 111L475 80L580 89L685 58"/>
            <path class="series" stroke="#a98ee3" d="M55 198L160 185L265 172L370 159L475 142L580 137L685 124"/>
            <path class="series" stroke="#6853b8" d="M55 219L160 210L265 202L370 197L475 187L580 181L685 173"/>
            ${xValues.map((x, i) => `<circle cx="${x}" cy="${yValues[i]}" r="4" fill="white" stroke="#e47896" stroke-width="2"/><text x="${x - 16}" y="256">8月${i + 3}日</text>`).join("")}
          </svg>
        </div>
      </article>
      <article class="card">
        <div class="card__header"><div><h3>抓取源健康</h3><p>最新一次抓取结果</p></div><button class="text-button" data-page-jump="sources" type="button">查看全部 ›</button></div>
        <div class="card__body">
          <div class="health-summary"><div class="summary-chip">正常<strong>12</strong></div><div class="summary-chip is-warning">异常<strong>2</strong></div><div class="summary-chip is-muted">待抓取<strong>1</strong></div></div>
          <ul class="health-list"><li><span>王一博 · 微博</span><span class="status status--success">正常</span></li><li><span>王一博工作室 · 微博</span><span class="status status--success">正常</span></li><li><span>王一博后援会 · 微博</span><span class="status status--danger">连续失败 3 次</span></li><li><span>檀健次工作室 · 微博</span><span class="status status--muted">待首次抓取</span></li></ul>
        </div>
      </article>
    </section>
    <section class="bottom-grid">
      <article class="card">
        <div class="card__header"><div><h3>推送投递</h3><p>最近 24 小时投递状态</p></div><button class="text-button" data-page-jump="deliveries" type="button">查看看板 ›</button></div>
        <div class="card__body"><div class="delivery-summary"><div class="donut"><div class="donut__label"><strong>96.2%</strong><span>成功率</span></div></div><div class="bar-list"><div class="bar-row"><span>成功</span><span class="bar-track"><span style="width:96.2%"></span></span><strong>1,286</strong></div><div class="bar-row"><span>重试</span><span class="bar-track"><span style="width:18%;background:#d88727"></span></span><strong>18</strong></div><div class="bar-row"><span>失败</span><span class="bar-track"><span style="width:12%;background:#c4526e"></span></span><strong>12</strong></div></div></div><div class="queue-foot"><span>待发队列</span><strong>24</strong></div></div>
      </article>
      <article class="card">
        <div class="card__header"><div><h3>idol 申请队列 <span class="badge badge--warning">待处理 3</span></h3><p>按支持人数排序</p></div><button class="text-button" data-page-jump="requests" type="button">查看全部 ›</button></div>
        <div class="card__body"><ul class="request-list">${state.requests.filter((item) => item.status === "pending").map((item) => `<li><span>${safe(item.name)}</span><span>${number(item.support)} 人支持</span><button class="button button--small" data-page-jump="requests" type="button">去审核</button></li>`).join("")}</ul></div>
      </article>
    </section>`;
}

function idolsPage() {
  const filtered = state.idols.filter((item) => state.idolStatus === "all" || item.status === state.idolStatus);
  const rows = filtered.map((item) => `
    <tr data-search="${safe(`${item.name} ${item.bio}`)}">
      <td><div class="entity"><span class="entity__avatar">${safe(item.initial)}</span><span><strong>${safe(item.name)}</strong><small>${safe(item.bio)}</small></span></div></td>
      <td>${statusBadge(item.status)}</td><td>${item.sources}</td><td>${number(item.posts)}</td><td>${number(item.guards)}</td><td>v${item.version}</td><td>${item.updatedAt}</td>
      <td><div class="actions"><button class="button button--small" data-action="edit-idol" data-id="${item.id}" type="button">编辑</button><button class="switch ${item.status === "enabled" ? "is-on" : ""}" data-action="toggle-idol" data-id="${item.id}" type="button" aria-label="${item.status === "enabled" ? "停用" : "启用"}${safe(item.name)}"></button></div></td>
    </tr>`).join("");
  return `
    ${pageHeading("idol 管理", "维护可守护对象；停用不会删除历史动态、守护或投递记录。", `<button class="button button--primary" data-action="add-idol" type="button">${icon("plus")} 新增 idol</button>`)}
    <section class="card data-card">
      <div class="data-card__toolbar"><div class="toolbar">${searchField("idol-search", "搜索名称或简介")}<label class="field">状态 <select id="idol-status"><option value="all">全部</option><option value="enabled" ${state.idolStatus === "enabled" ? "selected" : ""}>启用</option><option value="disabled" ${state.idolStatus === "disabled" ? "selected" : ""}>停用</option></select></label></div><span class="result-count" id="idol-count">共 ${filtered.length} 位</span></div>
      <div class="table-wrap"><table><thead><tr><th>idol</th><th>状态</th><th>动态源</th><th>动态数</th><th>守护人数</th><th>数据版本</th><th>最后更新</th><th>操作</th></tr></thead><tbody id="idol-table">${rows || '<tr><td colspan="8"><div class="empty">没有符合条件的 idol</div></td></tr>'}</tbody></table></div>${pagination()}
    </section>`;
}

function sourcesPage() {
  const filtered = state.sources.filter((item) => state.sourceStatus === "all" || item.status === state.sourceStatus);
  const rows = filtered.map((item) => `
    <tr data-search="${safe(`${item.idol} ${item.name} ${item.channel}`)}">
      <td><div class="entity"><span class="entity__avatar">${safe(item.idol.slice(0, 1))}</span><span><strong>${safe(item.name)}</strong><small>${safe(item.idol)} · ${safe(item.channel)}</small></span></div></td>
      <td><span class="url" title="${safe(item.url)}">${safe(item.url)}</span></td><td>${sourceStatusBadge(item.status)}</td><td>${safe(item.lastFetch)}</td><td>${safe(item.lastSuccess)}</td><td>${item.parsed} / ${item.added}</td><td>${item.failures ? `<span class="failure-count">${item.failures} 次</span>` : "0"}</td>
      <td><div class="actions"><button class="button button--small" data-action="manual-fetch" data-id="${item.id}" type="button">抓取验证</button><button class="button button--small button--neutral" data-action="edit-source" data-id="${item.id}" type="button">编辑</button><button class="switch ${item.enabled ? "is-on" : ""}" data-action="toggle-source" data-id="${item.id}" type="button" aria-label="${item.enabled ? "停用" : "启用"}${safe(item.name)}"></button></div></td>
    </tr>`).join("");
  return `
    ${pageHeading("动态源与健康度", "统一维护 RSS 地址、启停状态和最新抓取健康度；手动验证默认不产生推送。", `<button class="button button--primary" data-action="add-source" type="button">${icon("plus")} 新增动态源</button>`)}
    <section class="source-overview"><article class="card mini-stat"><span>启用来源</span><strong>14</strong></article><article class="card mini-stat"><span>异常来源</span><strong style="color:var(--red)">2</strong></article><article class="card mini-stat"><span>近 24h 新动态</span><strong>86</strong></article><article class="card mini-stat"><span>平均抓取耗时</span><strong>1.28s</strong></article></section>
    <section class="card data-card">
      <div class="data-card__toolbar"><div class="toolbar">${searchField("source-search", "搜索 idol 或来源名称")}<label class="field">健康状态 <select id="source-status"><option value="all">全部</option><option value="healthy" ${state.sourceStatus === "healthy" ? "selected" : ""}>正常</option><option value="failed" ${state.sourceStatus === "failed" ? "selected" : ""}>异常</option><option value="waiting" ${state.sourceStatus === "waiting" ? "selected" : ""}>待抓取</option><option value="disabled" ${state.sourceStatus === "disabled" ? "selected" : ""}>停用</option></select></label></div><span class="result-count" id="source-count">共 ${filtered.length} 个来源</span></div>
      <div class="table-wrap"><table><thead><tr><th>来源</th><th>RSS 地址（仅管理端）</th><th>健康状态</th><th>最近抓取</th><th>最近成功</th><th>解析 / 新增</th><th>连续失败</th><th>操作</th></tr></thead><tbody id="source-table">${rows || '<tr><td colspan="8"><div class="empty">没有符合条件的来源</div></td></tr>'}</tbody></table></div>${pagination()}
    </section>`;
}

function deliveriesPage() {
  const labels = { all: "全部", queued: "待发送", success: "成功", retry: "重试中", failed: "失败" };
  const filtered = state.deliveries.filter((item) => state.deliveryFilter === "all" || item.status === state.deliveryFilter);
  const tabs = Object.entries(labels).map(([value, label]) => `<button class="${state.deliveryFilter === value ? "is-active" : ""}" data-delivery-filter="${value}" type="button">${label}</button>`).join("");
  const rows = filtered.map((item) => `<tr><td><button class="text-button" data-action="delivery-detail" data-id="${item.id}" type="button">${item.id}</button></td><td>${safe(item.user)}</td><td>${safe(item.idol)}</td><td>${safe(item.post)}</td><td>${statusBadge(item.status)}</td><td>${item.retries}</td><td>${safe(item.sentAt)}</td><td>${safe(item.openedAt)}</td><td><button class="button button--small button--neutral" data-action="delivery-detail" data-id="${item.id}" type="button">查看</button></td></tr>`).join("");
  return `
    ${pageHeading("推送投递看板", "观察 outbox 积压、微信投递状态、失败原因与推送回访，不展示用户 OpenID。", `<button class="button button--neutral" data-toast="投递对账任务已提交" type="button">${icon("refresh")} 触发对账</button>`)}
    <section class="source-overview"><article class="card mini-stat"><span>待发队列</span><strong>24</strong></article><article class="card mini-stat"><span>最久等待</span><strong>42s</strong></article><article class="card mini-stat"><span>今日成功率</span><strong style="color:var(--green)">96.4%</strong></article><article class="card mini-stat"><span>今日回访率</span><strong>41.2%</strong></article></section>
    <section class="card data-card"><div class="data-card__toolbar"><div class="toolbar"><div class="filter-tabs">${tabs}</div><label class="field">时间 <select><option>今天</option><option>最近 7 天</option><option>最近 30 天</option></select></label><label class="field">idol <select><option>全部 idol</option>${state.idols.map((item) => `<option>${safe(item.name)}</option>`).join("")}</select></label></div><span class="result-count">${filtered.length} 条演示记录</span></div><div class="table-wrap"><table><thead><tr><th>投递编号</th><th>用户</th><th>idol</th><th>动态</th><th>状态</th><th>重试</th><th>发送时间</th><th>回访时间</th><th>操作</th></tr></thead><tbody>${rows || '<tr><td colspan="9"><div class="empty">当前状态下没有投递记录</div></td></tr>'}</tbody></table></div>${pagination()}</section>`;
}

function requestsPage() {
  const labels = { pending: "待审核", approved: "已通过", rejected: "已驳回", all: "全部" };
  const filtered = state.requests.filter((item) => state.requestFilter === "all" || item.status === state.requestFilter);
  const tabs = Object.entries(labels).map(([value, label]) => `<button class="${state.requestFilter === value ? "is-active" : ""}" data-request-filter="${value}" type="button">${label}</button>`).join("");
  const rows = filtered.map((item) => `<tr data-search="${safe(`${item.name} ${item.note}`)}"><td><strong>${safe(item.name)}</strong><div class="request-user">${safe(item.requester)}</div></td><td><strong>${number(item.support)}</strong><div class="progress"><span style="width:${Math.min(100, item.support / 3.5)}%"></span></div></td><td><div class="request-note">${safe(item.note || "未填写补充说明")}</div></td><td>${safe(item.createdAt)}</td><td>${statusBadge(item.status)}</td><td>${item.status === "pending" ? `<div class="actions"><button class="button button--small" data-action="approve-request" data-id="${item.id}" type="button">通过</button><button class="button button--small button--danger" data-action="reject-request" data-id="${item.id}" type="button">驳回</button></div>` : `<button class="button button--small button--neutral" data-action="request-detail" data-id="${item.id}" type="button">查看结果</button>`}</td></tr>`).join("");
  return `
    ${pageHeading("idol 申请审核", "优先处理支持人数高的申请；通过时创建或关联正式 idol。")}
    <section class="card data-card"><div class="data-card__toolbar"><div class="toolbar"><div class="filter-tabs">${tabs}</div>${searchField("request-search", "搜索申请名称")}</div><span class="result-count" id="request-count">${filtered.length} 条申请</span></div><div class="table-wrap"><table><thead><tr><th>申请 idol</th><th>支持人数</th><th>用户说明</th><th>申请时间</th><th>状态</th><th>操作</th></tr></thead><tbody id="request-table">${rows || '<tr><td colspan="6"><div class="empty">当前状态下没有申请</div></td></tr>'}</tbody></table></div>${pagination()}</section>`;
}

function auditPage() {
  const filtered = state.audits.filter((item) => state.auditResult === "all" || item.result === state.auditResult);
  const rows = filtered.map((item) => `<tr data-search="${safe(`${item.operator} ${item.action} ${item.resource} ${item.requestId} ${item.summary}`)}"><td>${safe(item.time)}</td><td>${safe(item.operator)}</td><td><span class="badge badge--violet">${safe(item.action)}</span></td><td><span class="audit-resource">${safe(item.resource)}</span></td><td>${statusBadge(item.result)}</td><td>${safe(item.requestId)}</td><td>${safe(item.summary)}</td><td><button class="button button--small button--neutral" data-action="audit-detail" data-id="${item.id}" type="button">详情</button></td></tr>`).join("");
  return `
    ${pageHeading("管理审计日志", "记录管理端写操作的操作者、资源、结果和摘要；不保存密码、token、OpenID 或服务密钥。", `<button class="button button--neutral" data-toast="演示日志已导出为 CSV" type="button">导出当前结果</button>`)}
    <section class="card data-card"><div class="data-card__toolbar"><div class="toolbar">${searchField("audit-search", "搜索操作、资源或 request ID")}<label class="field">结果 <select id="audit-result"><option value="all">全部</option><option value="success" ${state.auditResult === "success" ? "selected" : ""}>成功</option><option value="failed" ${state.auditResult === "failed" ? "selected" : ""}>失败</option></select></label><label class="field">时间 <select><option>今天</option><option>最近 7 天</option><option>最近 30 天</option></select></label></div><span class="result-count" id="audit-count">${filtered.length} 条记录</span></div><div class="table-wrap"><table><thead><tr><th>时间</th><th>操作者</th><th>操作类型</th><th>业务资源</th><th>结果</th><th>request ID</th><th>业务摘要</th><th>操作</th></tr></thead><tbody id="audit-table">${rows}</tbody></table></div>${pagination()}</section>`;
}

const renderers = { dashboard: dashboardPage, idols: idolsPage, sources: sourcesPage, deliveries: deliveriesPage, requests: requestsPage, audit: auditPage };

function renderNavigation() {
  $$(".nav__item").forEach((button) => {
    const meta = pageMeta[button.dataset.page];
    button.innerHTML = `${icon(meta.icon)}<span>${meta.label}</span>${meta.badge ? `<span class="nav__badge">${meta.badge}</span>` : ""}`;
    button.classList.toggle("is-active", button.dataset.page === state.page);
  });
}

function renderPage({ focus = true } = {}) {
  renderNavigation();
  $("#page-title").textContent = pageMeta[state.page].label;
  $("#page-root").innerHTML = renderers[state.page]();
  if (focus) $("#page-root").focus({ preventScroll: true });
}

function navigate(page) {
  if (!renderers[page]) return;
  state.page = page;
  renderPage();
  window.scrollTo(0, 0);
  $("#sidebar").classList.remove("is-open");
  history.replaceState(null, "", `#${page}`);
}

let toastTimer;
function showToast(message) {
  const toast = $("#toast");
  toast.textContent = message;
  toast.classList.add("is-visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("is-visible"), 2200);
}

function openModal({ eyebrow = "管理操作", title, body, confirm = "保存", danger = false, onConfirm }) {
  const modal = $("#modal");
  $("#modal-eyebrow").textContent = eyebrow;
  $("#modal-title").textContent = title;
  $("#modal-body").innerHTML = body;
  $("#modal-footer").innerHTML = `<button class="button button--neutral" value="cancel" type="submit">取消</button><button class="button ${danger ? "button--danger" : "button--primary"}" id="modal-confirm" type="button">${confirm}</button>`;
  $("#modal-confirm").addEventListener("click", () => {
    if (onConfirm?.() === false) return;
    modal.close();
  }, { once: true });
  modal.showModal();
}

function openDrawer({ eyebrow = "详情", title, body }) {
  $("#drawer-eyebrow").textContent = eyebrow;
  $("#drawer-title").textContent = title;
  $("#drawer-body").innerHTML = body;
  $("#drawer").showModal();
}

function idolForm(item = {}) {
  return `<div class="form-grid" id="idol-form"><label class="form-field"><span>名称 *</span><input name="name" value="${safe(item.name || "")}" placeholder="请输入 idol 名称" required /></label><label class="form-field"><span>状态</span><select name="status"><option value="enabled" ${item.status !== "disabled" ? "selected" : ""}>启用</option><option value="disabled" ${item.status === "disabled" ? "selected" : ""}>停用</option></select></label><label class="form-field form-field--wide"><span>简介</span><textarea name="bio" placeholder="简要描述身份或业务标签">${safe(item.bio || "")}</textarea></label><label class="form-field form-field--wide"><span>头像地址</span><input name="avatar" type="url" placeholder="https://..." /><p class="form-hint">原型仅展示字段；正式版上传后应保存受控资源地址。</p></label></div>`;
}

function editIdol(id) {
  const item = state.idols.find((idol) => idol.id === id);
  openModal({ title: item ? `编辑 ${item.name}` : "新增 idol", body: idolForm(item), confirm: item ? "保存修改" : "创建 idol", onConfirm: () => {
    const form = $("#idol-form");
    const name = $("[name=name]", form).value.trim();
    if (!name) { showToast("请填写 idol 名称"); return false; }
    const bio = $("[name=bio]", form).value.trim();
    const status = $("[name=status]", form).value;
    if (item) Object.assign(item, { name, initial: name.slice(0, 1), bio, status, version: item.version + 1, updatedAt: "刚刚" });
    else state.idols.unshift({ id: Date.now(), name, initial: name.slice(0, 1), bio, status, sources: 0, posts: 0, guards: 0, version: 1, updatedAt: "刚刚" });
    renderPage({ focus: false });
    showToast(item ? "idol 信息已更新（原型数据）" : "idol 已创建（原型数据）");
  }});
}

function toggleIdol(id) {
  const item = state.idols.find((idol) => idol.id === id);
  if (!item) return;
  const disabling = item.status === "enabled";
  const body = disabling
    ? `<div class="impact-box"><strong>影响范围</strong><br />${item.sources} 个动态源将停止抓取；${number(item.guards)} 位守护用户不再收到新推送。历史动态、守护关系和投递记录都会保留。</div>`
    : "<p>启用后，该 idol 的已启用来源会在下一轮调度恢复抓取。</p>";
  openModal({ eyebrow: "状态变更", title: `${disabling ? "停用" : "启用"} ${item.name}`, body, confirm: disabling ? "确认停用" : "确认启用", danger: disabling, onConfirm: () => {
    item.status = disabling ? "disabled" : "enabled";
    item.version += 1;
    item.updatedAt = "刚刚";
    renderPage({ focus: false });
    showToast(`${item.name} 已${disabling ? "停用" : "启用"}（原型数据）`);
  }});
}

function sourceForm(item = {}) {
  return `<div class="form-grid" id="source-form"><label class="form-field"><span>所属 idol *</span><select name="idol">${state.idols.map((idol) => `<option ${idol.name === item.idol ? "selected" : ""}>${safe(idol.name)}</option>`).join("")}</select></label><label class="form-field"><span>渠道 *</span><select name="channel"><option ${item.channel === "微博" ? "selected" : ""}>微博</option><option>工作室</option><option>行程</option></select></label><label class="form-field form-field--wide"><span>展示名称 *</span><input name="name" value="${safe(item.name || "")}" placeholder="例如：王一博工作室 · 微博" /></label><label class="form-field form-field--wide"><span>RSS 地址 *</span><input name="url" type="url" value="${safe(item.url || "")}" placeholder="https://rss.example.com/..." /><p class="form-hint">保存前应由服务端执行地址、重定向、内网目标和响应大小安全校验。</p></label></div>`;
}

function editSource(id) {
  const item = state.sources.find((source) => source.id === id);
  openModal({ title: item ? `编辑 ${item.name}` : "新增动态源", body: sourceForm(item), confirm: item ? "保存并校验" : "创建并校验", onConfirm: () => {
    const form = $("#source-form");
    const name = $("[name=name]", form).value.trim();
    const url = $("[name=url]", form).value.trim();
    if (!name || !url.startsWith("https://")) { showToast("请填写名称和有效 HTTPS 地址"); return false; }
    const idol = $("[name=idol]", form).value;
    const channel = $("[name=channel]", form).value;
    if (item) Object.assign(item, { name, url, idol, channel });
    else state.sources.unshift({ id: Date.now(), idol, name, channel, url, status: "waiting", enabled: true, lastFetch: "尚未抓取", lastSuccess: "—", parsed: 0, added: 0, failures: 0 });
    renderPage({ focus: false });
    showToast("动态源已保存，安全校验通过（原型）");
  }});
}

function toggleSource(id) {
  const item = state.sources.find((source) => source.id === id);
  if (!item) return;
  item.enabled = !item.enabled;
  item.status = item.enabled ? "waiting" : "disabled";
  renderPage({ focus: false });
  showToast(`${item.name} 已${item.enabled ? "启用" : "停用"}（原型数据）`);
}

function manualFetch(id) {
  const item = state.sources.find((source) => source.id === id);
  if (!item) return;
  openModal({ eyebrow: "抓取验证", title: item.name, body: '<div class="impact-box">本次操作复用定时抓取逻辑，但只做验证与入库检查，默认不创建推送任务。</div><div class="fetch-result"><div><strong>—</strong><span>解析数量</span></div><div><strong>—</strong><span>新增数量</span></div><div><strong>等待</strong><span>执行状态</span></div></div>', confirm: "开始抓取", onConfirm: () => {
    showToast("正在抓取并校验来源…");
    setTimeout(() => {
      const added = item.failures ? 0 : 1;
      item.status = "healthy";
      item.enabled = true;
      item.lastFetch = "刚刚";
      item.lastSuccess = "刚刚";
      item.parsed = 20;
      item.added = added;
      item.failures = 0;
      if (state.page === "sources") renderPage({ focus: false });
      showToast(`抓取成功：解析 20 条，新增 ${added} 条；未产生推送`);
    }, 1100);
  }});
}

function deliveryDetail(id) {
  const item = state.deliveries.find((delivery) => delivery.id === id);
  if (!item) return;
  openDrawer({ eyebrow: "投递详情", title: item.id, body: `<dl class="detail-list"><div><dt>用户标识</dt><dd>${safe(item.user)}<br /><small>管理端不展示 OpenID</small></dd></div><div><dt>idol</dt><dd>${safe(item.idol)}</dd></div><div><dt>动态编号</dt><dd>${safe(item.post)}</dd></div><div><dt>投递状态</dt><dd>${statusBadge(item.status)}</dd></div><div><dt>重试次数</dt><dd>${item.retries}</dd></div><div><dt>发送时间</dt><dd>${safe(item.sentAt)}</dd></div><div><dt>回访时间</dt><dd>${safe(item.openedAt)}</dd></div><div><dt>失败原因</dt><dd>${safe(item.reason)}</dd></div></dl><ol class="timeline"><li><strong>创建投递意图</strong><span>动态与 outbox 同事务写入</span></li><li><strong>Worker 领取任务</strong><span>额度预留成功</span></li><li><strong>调用微信接口</strong><span>${item.status === "success" ? "微信接口返回成功" : safe(item.reason)}</span></li>${item.openedAt !== "—" ? `<li><strong>用户回访</strong><span>${safe(item.openedAt)}</span></li>` : ""}</ol>` });
}

function reviewRequest(id, approved) {
  const item = state.requests.find((request) => request.id === id);
  if (!item) return;
  const body = approved
    ? '<div class="form-grid" id="review-form"><label class="form-field form-field--wide"><span>通过方式</span><select name="mode"><option>创建新的正式 idol</option><option>关联已有 idol</option></select></label><label class="form-field form-field--wide"><span>审核备注</span><textarea name="note">申请通过，进入正式来源配置流程。</textarea></label><div class="form-field form-field--wide"><div class="impact-box">通过后仍需配置至少一个可用动态源；未配置来源前，小程序不会展示为可守护对象。</div></div></div>'
    : '<div class="form-grid" id="review-form"><label class="form-field form-field--wide"><span>驳回原因 *</span><textarea name="note" placeholder="该原因会展示给申请用户"></textarea></label></div>';
  openModal({ eyebrow: "申请审核", title: `${approved ? "通过" : "驳回"}「${item.name}」`, body, confirm: approved ? "确认通过" : "确认驳回", danger: !approved, onConfirm: () => {
    const note = $("[name=note]", $("#review-form")).value.trim();
    if (!approved && !note) { showToast("请填写驳回原因"); return false; }
    item.status = approved ? "approved" : "rejected";
    item.reviewNote = note;
    pageMeta.requests.badge = state.requests.filter((request) => request.status === "pending").length;
    renderPage({ focus: false });
    showToast(`申请已${approved ? "通过" : "驳回"}（原型数据）`);
  }});
}

function requestDetail(id) {
  const item = state.requests.find((request) => request.id === id);
  if (!item) return;
  openDrawer({ eyebrow: "审核结果", title: item.name, body: `<dl class="detail-list"><div><dt>当前状态</dt><dd>${statusBadge(item.status)}</dd></div><div><dt>支持人数</dt><dd>${number(item.support)} 人</dd></div><div><dt>申请用户</dt><dd>${safe(item.requester)}</dd></div><div><dt>用户说明</dt><dd>${safe(item.note || "未填写")}</dd></div><div><dt>审核备注</dt><dd>${safe(item.reviewNote || (item.status === "approved" ? "申请通过，进入正式来源配置流程。" : "申请与现有条目重复。"))}</dd></div></dl>` });
}

function auditDetail(id) {
  const item = state.audits.find((audit) => audit.id === id);
  if (!item) return;
  openDrawer({ eyebrow: "审计详情", title: item.id, body: `<dl class="detail-list"><div><dt>操作时间</dt><dd>${safe(item.time)}</dd></div><div><dt>操作者</dt><dd>${safe(item.operator)}</dd></div><div><dt>操作类型</dt><dd>${safe(item.action)}</dd></div><div><dt>业务资源</dt><dd>${safe(item.resource)}</dd></div><div><dt>执行结果</dt><dd>${statusBadge(item.result)}</dd></div><div><dt>request ID</dt><dd>${safe(item.requestId)}</dd></div><div><dt>业务摘要</dt><dd>${safe(item.summary)}</dd></div></dl><div class="code-block"><strong>修改前</strong><br />${safe(item.before)}<br /><br /><strong>修改后</strong><br />${safe(item.after)}</div>` });
}

// 原型只过滤当前页面已渲染的演示数据；真实后台应由服务端分页和权限过滤。
function filterVisibleRows(input, table, counter, suffix) {
  const keyword = input.value.trim().toLowerCase();
  let visible = 0;
  $$("tr[data-search]", table).forEach((row) => {
    const matched = !keyword || row.dataset.search.toLowerCase().includes(keyword);
    row.hidden = !matched;
    if (matched) visible += 1;
  });
  if (counter) counter.textContent = `${visible} ${suffix}`;
}

function handlePageClick(event) {
  const button = event.target.closest("button");
  if (!button) return;
  if (button.dataset.pageJump) return navigate(button.dataset.pageJump);
  if (button.dataset.toast) return showToast(button.dataset.toast);
  if (button.dataset.deliveryFilter) { state.deliveryFilter = button.dataset.deliveryFilter; return renderPage({ focus: false }); }
  if (button.dataset.requestFilter) { state.requestFilter = button.dataset.requestFilter; return renderPage({ focus: false }); }
  const id = Number(button.dataset.id);
  const actions = {
    "add-idol": () => editIdol(),
    "edit-idol": () => editIdol(id),
    "toggle-idol": () => toggleIdol(id),
    "add-source": () => editSource(),
    "edit-source": () => editSource(id),
    "toggle-source": () => toggleSource(id),
    "manual-fetch": () => manualFetch(id),
    "delivery-detail": () => deliveryDetail(button.dataset.id),
    "approve-request": () => reviewRequest(id, true),
    "reject-request": () => reviewRequest(id, false),
    "request-detail": () => requestDetail(id),
    "audit-detail": () => auditDetail(button.dataset.id),
    "refresh-dashboard": () => { $("#update-time").textContent = "数据更新于 刚刚"; showToast("指标数据已刷新"); },
  };
  actions[button.dataset.action]?.();
}

function handlePageChange(event) {
  if (event.target.id === "idol-status") { state.idolStatus = event.target.value; renderPage({ focus: false }); }
  if (event.target.id === "source-status") { state.sourceStatus = event.target.value; renderPage({ focus: false }); }
  if (event.target.id === "audit-result") { state.auditResult = event.target.value; renderPage({ focus: false }); }
  if (event.target.id === "dashboard-range") showToast(`指标范围已切换为${event.target.value}`);
}

function handlePageInput(event) {
  const filters = {
    "idol-search": ["#idol-table", "#idol-count", "位"],
    "source-search": ["#source-table", "#source-count", "个来源"],
    "request-search": ["#request-table", "#request-count", "条申请"],
    "audit-search": ["#audit-table", "#audit-count", "条记录"],
  };
  const target = filters[event.target.id];
  if (target) filterVisibleRows(event.target, $(target[0]), $(target[1]), target[2]);
}

function toggleAccountMenu() {
  const existing = $(".account-menu");
  if (existing) return existing.remove();
  const menu = document.createElement("div");
  menu.className = "account-menu";
  menu.innerHTML = `<button type="button" data-account-action="profile">${icon("profile")} 当前管理员</button><button type="button" data-account-action="logout">${icon("logout")} 退出登录</button>`;
  menu.addEventListener("click", (event) => {
    const action = event.target.closest("button")?.dataset.accountAction;
    if (action === "profile") showToast("管理员资料页不在本轮业务范围内");
    if (action === "logout") logout();
    menu.remove();
  });
  document.body.append(menu);
}

function showApplication(admin) {
  $("#login-screen").classList.add("is-hidden");
  $("#app-shell").classList.remove("is-hidden");
  $$('[data-admin-name]').forEach((element) => { element.textContent = admin.username; });
  const hashPage = location.hash.slice(1);
  state.page = renderers[hashPage] ? hashPage : "dashboard";
  renderPage();
}

function showLogin(message = "", error = false) {
  $("#app-shell").classList.add("is-hidden");
  $("#login-screen").classList.remove("is-hidden");
  const status = $("#login-message");
  status.textContent = message;
  status.classList.toggle("is-error", error);
  history.replaceState(null, "", location.pathname);
}

async function adminRequest(path, options = {}) {
  const token = sessionStorage.getItem(ADMIN_TOKEN_KEY);
  const response = await fetch(path, {
    ...options,
    headers: {
      Accept: "application/json",
      ...(options.body ? { "Content-Type": "application/json" } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  });
  const body = await response.json().catch(() => null);
  if (response.status === 401) {
    // 401 统一清理管理员 token；禁止继续展示缓存的管理数据。
    sessionStorage.removeItem(ADMIN_TOKEN_KEY);
    showLogin("登录已失效，请重新登录。", true);
  }
  if (!response.ok || !body?.ok) {
    throw new Error(body?.error?.message || "管理后台暂时不可用");
  }
  return body.data;
}

async function login() {
  const form = $("#login-form");
  const button = $('button[type="submit"]', form);
  const status = $("#login-message");
  button.disabled = true;
  status.textContent = "正在验证…";
  status.classList.remove("is-error");
  try {
    const response = await fetch("/admin/v1/auth/login", {
      method: "POST",
      headers: { Accept: "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({
        username: $("#login-account").value.trim(),
        password: $("#login-password").value,
      }),
    });
    const body = await response.json().catch(() => null);
    if (!response.ok || !body?.ok) {
      throw new Error(body?.error?.message || "登录失败");
    }
    // sessionStorage 限制 token 生命周期到当前标签页；不使用长期 localStorage。
    sessionStorage.setItem(ADMIN_TOKEN_KEY, body.data.token);
    $("#login-password").value = "";
    showApplication(body.data.admin);
  } catch (error) {
    status.textContent = error instanceof Error ? error.message : "登录失败";
    status.classList.add("is-error");
  } finally {
    button.disabled = false;
  }
}

async function logout() {
  try {
    await adminRequest("/admin/v1/auth/logout", { method: "POST" });
  } catch (error) {
    // 即使网络失败也删除本地 token；服务端会话仍受短 TTL 限制，可由其他管理员吊销。
  } finally {
    sessionStorage.removeItem(ADMIN_TOKEN_KEY);
    showLogin("已退出登录。", false);
  }
}

async function restoreSession() {
  if (!sessionStorage.getItem(ADMIN_TOKEN_KEY)) return;
  try {
    showApplication(await adminRequest("/admin/v1/me"));
  } catch (error) {
    if (sessionStorage.getItem(ADMIN_TOKEN_KEY)) {
      showLogin(error instanceof Error ? error.message : "管理后台暂时不可用", true);
    }
  }
}

async function initialize() {
  $("#toggle-password").innerHTML = icon("eye");
  $("#mobile-menu").innerHTML = icon("menu");
  $(".notification-button").innerHTML = icon("bell");
  $("#update-time").textContent = "数据更新于 11:20";
  $("#login-form").addEventListener("submit", (event) => { event.preventDefault(); void login(); });
  $("#toggle-password").addEventListener("click", () => {
    const input = $("#login-password");
    const visible = input.type === "text";
    input.type = visible ? "password" : "text";
    $("#toggle-password").innerHTML = icon(visible ? "eye" : "eyeOff");
    $("#toggle-password").setAttribute("aria-label", visible ? "显示密码" : "隐藏密码");
  });
  $("#mobile-menu").addEventListener("click", () => $("#sidebar").classList.toggle("is-open"));
  $("#account-trigger").addEventListener("click", toggleAccountMenu);
  $("#sidebar-account").addEventListener("click", toggleAccountMenu);
  $("#page-root").addEventListener("click", handlePageClick);
  $("#page-root").addEventListener("change", handlePageChange);
  $("#page-root").addEventListener("input", handlePageInput);
  $(".nav").addEventListener("click", (event) => {
    const button = event.target.closest("[data-page]");
    if (button) navigate(button.dataset.page);
  });
  document.addEventListener("click", (event) => {
    const toastButton = event.target.closest("[data-toast]");
    if (toastButton && !$("#page-root").contains(toastButton)) showToast(toastButton.dataset.toast);
  });
  window.addEventListener("hashchange", () => {
    const page = location.hash.slice(1);
    if (renderers[page] && page !== state.page) navigate(page);
  });
  await restoreSession();
}

void initialize();
