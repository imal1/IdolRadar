import { state } from '../state';
import type { MetricsTrendPoint } from '../types';
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

const RANGE_OPTIONS: [number, string][] = [
  [7, '最近 7 天'],
  [30, '最近 30 天'],
  [90, '最近 90 天'],
];

const TREND_SERIES: [keyof MetricsTrendPoint, string, string][] = [
  ['guarded', '完成守护', '#e47896'],
  ['subscribed', '完成订阅', '#a98ee3'],
  ['opened', '推送回访', '#6853b8'],
];

/** 折线图几何：左侧留出刻度文字的宽度，底部留出日期标签。 */
const CHART = { left: 48, right: 700, top: 25, bottom: 237 };

function metricCard(label: string, value: string, note: string, iconName: string): string {
  return `
    <article class="metric-card">
      <div class="metric-card__top"><span class="metric-card__label">${safe(label)}</span><span class="metric-card__icon">${icon(iconName)}</span></div>
      <div class="metric-card__value">${safe(value)}</div>
      <div class="metric-card__meta"><span>${safe(note)}</span></div>
    </article>`;
}

/** 把一条序列映射成折线路径；上界按三条序列的全局最大值统一，便于横向比较。 */
export function seriesPath(points: MetricsTrendPoint[], key: keyof MetricsTrendPoint, top: number): string {
  const stepX = points.length > 1 ? (CHART.right - CHART.left) / (points.length - 1) : 0;
  const height = CHART.bottom - CHART.top;
  return points.map((point, index) => {
    const x = CHART.left + stepX * index;
    const y = CHART.bottom - (Number(point[key]) / top) * height;
    return `${index === 0 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`;
  }).join('');
}

function trendChart(points: MetricsTrendPoint[]): string {
  if (points.length === 0) {
    return '<p class="chart-empty">所选区间内还没有数据。</p>';
  }
  // 全为 0 时用 1 作上界，避免除零把整条线画到坐标轴外。
  const top = Math.max(1, ...points.flatMap((point) => TREND_SERIES.map(([key]) => Number(point[key]))));
  const gridLines = [0, 0.25, 0.5, 0.75, 1].map((fraction) => {
    const y = CHART.bottom - fraction * (CHART.bottom - CHART.top);
    return `M${CHART.left} ${y.toFixed(1)}H${CHART.right}`;
  }).join('');
  const axisLabels = [1, 0.75, 0.5, 0.25, 0].map((fraction) => {
    const y = CHART.bottom - fraction * (CHART.bottom - CHART.top);
    return `<text x="12" y="${(y + 4).toFixed(1)}">${number(Math.round(top * fraction))}</text>`;
  }).join('');
  // 日期标签最多显示 7 个，区间拉长到 90 天时不会糊成一片。
  const labelStep = Math.max(1, Math.ceil(points.length / 7));
  const stepX = points.length > 1 ? (CHART.right - CHART.left) / (points.length - 1) : 0;
  const dateLabels = points.map((point, index) => {
    if (index % labelStep !== 0) return '';
    const x = CHART.left + stepX * index;
    const [, month, day] = point.date.split('-');
    return `<text x="${(x - 16).toFixed(1)}" y="256">${Number(month)}月${Number(day)}日</text>`;
  }).join('');

  return `
    <svg class="chart" viewBox="0 0 720 260" role="img" aria-label="区间内用户闭环趋势折线图">
      <path class="grid-line" d="${gridLines}"/>
      ${axisLabels}
      ${TREND_SERIES.map(([key, , color]) => `<path class="series" stroke="${color}" d="${seriesPath(points, key, top)}"/>`).join('')}
      ${dateLabels}
    </svg>`;
}

/**
 * 概览页。核心指标、漏斗与趋势折线均来自 /admin/v1/metrics 的真实聚合。
 *
 * <p>口径直接写在页面上：闭环转化率按注册队列计算，回访率限定送达后 24 小时内。
 * 两者与 PRD 的成功指标定义一致，也与投递看板上没有时间窗的 openRate 有意区分。
 */
export function render(): string {
  const metrics = state.metrics;
  const funnel = metrics?.funnel;
  const delivery = metrics?.delivery;
  const pending = (value: number | undefined, suffix = '') => (metrics ? `${value ?? 0}${suffix}` : '—');
  return `
    ${pageHeading('核心指标', 'PRD 定义的两个转化类指标，全部由服务端业务数据聚合，不依赖前端埋点。', `<label class="field">${icon('calendar')}<select id="dashboard-range">${RANGE_OPTIONS.map(([days, label]) => `<option value="${days}"${state.metricsRange === days ? ' selected' : ''}>${label}</option>`).join('')}</select></label><button class="button button--neutral" data-action="refresh-dashboard" type="button">${icon('refresh')} 刷新数据</button>`)}
    <section class="metric-grid" aria-label="核心指标">
      ${metricCard('新增用户', pending(funnel?.newUsers), `区间内注册`, 'users')}
      ${metricCard('闭环转化率', pending(funnel?.conversionRate, '%'), '新增用户中完成选 idol 加授权', 'trend')}
      ${metricCard('推送回访率', pending(delivery?.openRate, '%'), '送达后 24 小时内打开小程序', 'refresh')}
      ${metricCard('已送达推送', pending(delivery?.sent), `其中 ${pending(delivery?.openedWithin24h)} 次被打开`, 'send')}
    </section>
    <section class="card metric-funnel">
      <div class="card__header"><div><h3>闭环漏斗</h3><p>看清用户在哪一步流失</p></div></div>
      <div class="card__body funnel-row">
        <div class="funnel-step"><span>注册</span><strong>${pending(funnel?.newUsers)}</strong></div>
        <div class="funnel-arrow">→ ${pending(funnel?.guardRate, '%')}</div>
        <div class="funnel-step"><span>选了 idol</span><strong>${pending(funnel?.guarded)}</strong></div>
        <div class="funnel-arrow">→ ${pending(funnel?.subscribeRate, '%')}</div>
        <div class="funnel-step"><span>完成授权</span><strong>${pending(funnel?.subscribed)}</strong></div>
      </div>
      <p class="metric-note">口径：分母是区间内注册的新用户，分子是这些人中后来完成对应步骤的人数。回访率限定在投递完成后 24 小时内，与投递看板上不设时间窗的成功率口径不同。</p>
    </section>
    <section class="dashboard-grid">
      <article class="card">
        <div class="card__header"><div><h3>用户闭环趋势</h3><p>按 Asia/Shanghai 自然日统计</p></div><div class="chart-legend">${TREND_SERIES.map(([, label, color]) => `<span><i class="legend-dot" style="background:${color}"></i>${label}</span>`).join('')}</div></div>
        <div class="card__body">
          ${trendChart(metrics?.trend ?? [])}
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
