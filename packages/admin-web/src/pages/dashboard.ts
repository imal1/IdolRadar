import { state } from '../state';
import { icon, number, pageHeading, safe } from '../ui';
import { healthStatusText } from './sources';

/** 首页投递条形：按窗口内最大一项归一化，量级悬殊时失败条也仍然可见。 */
function deliveryBars(): string {
  const summary = state.deliverySummary;
  const rows: [string, number, string][] = [
    ['成功', summary.sent ?? 0, '#4c9a72'],
    ['重试', summary.retryable ?? 0, '#d88727'],
    ['失败', (summary.failed ?? 0) + (summary.uncertain ?? 0), '#c4526e'],
  ];
  const top = Math.max(1, ...rows.map(([, value]) => value));
  return rows.map(([label, value, color]) => `<div class="bar-row"><span>${label}</span><span class="bar-track"><span style="width:${Math.round((value / top) * 100)}%;background:${color}"></span></span><strong>${number(value)}</strong></div>`).join('');
}

/**
 * 概览页。核心指标卡与趋势折线目前是设计稿写死的演示数据，只有投递条形来自真实接口；
 * 指标聚合接口尚未提供，等接口就绪后替换，勿据此判断线上情况。
 */
export function render(): string {
  const metric = (label: string, value: string, trend: string, iconName: string, note: string) => `
    <article class="metric-card">
      <div class="metric-card__top"><span class="metric-card__label">${label}</span><span class="metric-card__icon">${icon(iconName)}</span></div>
      <div class="metric-card__value">${value}</div>
      <div class="metric-card__meta"><span class="trend">↑ ${trend}</span><span>${note}</span></div>
    </article>`;
  const xValues = [55, 160, 265, 370, 475, 580, 685];
  const yValues = [163, 143, 127, 111, 80, 89, 58];
  return `
    ${pageHeading('今日概览', '从用户守护到推送回访，关注真正完成的业务闭环。', `<label class="field">${icon('calendar')}<select id="dashboard-range"><option>最近 7 天</option><option>最近 30 天</option><option>本季度</option></select></label><button class="button button--neutral" data-action="refresh-dashboard" type="button">${icon('refresh')} 刷新数据</button>`)}
    <section class="metric-grid" aria-label="核心指标">
      ${metric('新增用户', '128', '+12.6%', 'users', '较上一周期')}
      ${metric('闭环转化率', '63.8%', '+5.2%', 'trend', '守护至回访')}
      ${metric('推送成功率', '96.4%', '+1.8%', 'send', '近 1,316 次')}
      ${metric('推送回访率', '41.2%', '+8.4%', 'refresh', '打开原文')}
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
            ${xValues.map((x, i) => `<circle cx="${x}" cy="${yValues[i]}" r="4" fill="white" stroke="#e47896" stroke-width="2"/><text x="${x - 16}" y="256">8月${i + 3}日</text>`).join('')}
          </svg>
        </div>
      </article>
      <article class="card">
        <div class="card__header"><div><h3>抓取源健康</h3><p>最新一次抓取结果</p></div><button class="text-button" data-page-jump="sources" type="button">查看全部 ›</button></div>
        <div class="card__body">
          <div class="health-summary"><div class="summary-chip">正常<strong>${state.sourceSummary.healthy ?? 0}</strong></div><div class="summary-chip is-warning">异常<strong>${(state.sourceSummary.failed ?? 0) + (state.sourceSummary.stale ?? 0)}</strong></div><div class="summary-chip is-muted">待抓取<strong>${state.sourceSummary.waiting ?? 0}</strong></div></div>
          <ul class="health-list">${state.sources.slice(0, 5).map((item) => `<li><span>${safe(item.displayName)}</span>${healthStatusText(item)}</li>`).join('') || '<li><span>暂无来源</span><span class="status status--muted">—</span></li>'}</ul>
        </div>
      </article>
    </section>
    <section class="bottom-grid">
      <article class="card">
        <div class="card__header"><div><h3>推送投递</h3><p>最近 24 小时投递状态</p></div><button class="text-button" data-page-jump="deliveries" type="button">查看看板 ›</button></div>
        <div class="card__body"><div class="delivery-summary"><div class="donut"><div class="donut__label"><strong>${state.deliverySummary.successRate ?? 0}%</strong><span>成功率</span></div></div><div class="bar-list">${deliveryBars()}</div></div><div class="queue-foot"><span>待发队列</span><strong>${number(state.deliveryQueue.backlog ?? 0)}</strong></div></div>
      </article>
      <article class="card">
        <div class="card__header"><div><h3>idol 申请队列 <span class="badge badge--warning">待处理 ${state.pendingCount}</span></h3><p>按支持人数排序</p></div><button class="text-button" data-page-jump="requests" type="button">查看全部 ›</button></div>
        <div class="card__body"><ul class="request-list">${state.requests.filter((item) => item.status === 'pending').slice(0, 5).map((item) => `<li><span>${safe(item.displayName)}</span><span>${number(item.supporterCount)} 人支持</span><button class="button button--small" data-page-jump="requests" type="button">去审核</button></li>`).join('') || '<li><span>暂无待处理申请</span></li>'}</ul></div>
      </article>
    </section>`;
}
