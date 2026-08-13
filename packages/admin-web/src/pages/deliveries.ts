import { state } from '../state';
import type { Delivery } from '../types';
import { icon, number, openDrawer, pageHeading, safe, statusBadge, timeText } from '../ui';

/** 投递没有单列主键，(post_id, user_id) 才是账本主键，详情按这个复合键回查。 */
export function deliveryKey(item: Pick<Delivery, 'postId' | 'userId'>): string {
  return `${item.postId} ${item.userId}`;
}

const ranges: Record<string, string> = { 24: '最近 24 小时', 168: '最近 7 天', 720: '最近 30 天' };

export function render(): string {
  const labels: Record<string, string> = { all: '全部', sent: '成功', retryable: '重试中', failed: '失败', uncertain: '结果未知', reserved: '待发送', stuck: '反复重试' };
  const summary = state.deliverySummary;
  const queue = state.deliveryQueue;
  const tabs = Object.entries(labels).map(([value, label]) => `<button class="${state.deliveryFilter === value ? 'is-active' : ''}" data-delivery-filter="${value}" type="button">${label}${summary[value] ? ` ${summary[value]}` : ''}</button>`).join('');
  const rows = state.deliveries.map((item) => `<tr><td><button class="text-button" data-action="delivery-detail" data-id="${safe(deliveryKey(item))}" type="button">${safe(item.postTitle || item.postId)}</button><div class="request-user">${safe(item.postId)}</div></td><td>${safe(item.userId.slice(0, 8))}</td><td>${safe(item.idolName)}</td><td>${statusBadge(item.status)}${item.errorCode ? `<small>${safe(item.errorCode)}</small>` : ''}</td><td>${item.attemptCount}</td><td>${timeText(item.createdAt)}</td><td>${timeText(item.finishedAt)}</td><td>${item.openCount > 0 ? timeText(item.firstOpenedAt) : '—'}</td><td><button class="button button--small button--neutral" data-action="delivery-detail" data-id="${safe(deliveryKey(item))}" type="button">查看</button></td></tr>`).join('');
  // 失败原因按最大值归一化成条形，管理员一眼看出主导错误码，而不是读一列数字。
  const topFailure = Math.max(1, ...state.deliveryFailures.map((item) => item.total));
  const failureRows = state.deliveryFailures.map((item) => `<div class="bar-row"><span>${safe(item.errorCode)}</span><span class="bar-track"><span style="width:${Math.round((item.total / topFailure) * 100)}%;background:#c4526e"></span></span><strong>${number(item.total)}</strong></div>`).join('');
  return `
    ${pageHeading('推送投递看板', '观察 outbox 积压、微信投递状态、失败原因与推送回访，不展示用户 OpenID。', `<button class="button button--neutral" data-action="refresh-deliveries" type="button">${icon('refresh')} 刷新</button>`)}
    <section class="source-overview"><article class="card mini-stat"><span>待发队列</span><strong${queue.backlog ? ' style="color:var(--red)"' : ''}>${number(queue.backlog ?? 0)}</strong></article><article class="card mini-stat"><span>最久等待</span><strong>${timeText(queue.oldestQueuedAt)}</strong></article><article class="card mini-stat"><span>投递成功率</span><strong style="color:var(--green)">${summary.successRate ?? 0}%</strong></article><article class="card mini-stat"><span>推送回访率</span><strong>${summary.openRate ?? 0}%</strong></article></section>
    <section class="dashboard-grid">
      <article class="card"><div class="card__header"><div><h3>状态分布</h3><p>${safe(ranges[state.deliveryRange] || '')}内创建的投递</p></div></div><div class="card__body"><div class="health-summary"><div class="summary-chip">成功<strong>${number(summary.sent ?? 0)}</strong></div><div class="summary-chip is-warning">重试中<strong>${number(summary.retryable ?? 0)}</strong></div><div class="summary-chip is-warning">失败<strong>${number(summary.failed ?? 0)}</strong></div><div class="summary-chip is-muted">发送中<strong>${number((summary.sending ?? 0) + (summary.reserved ?? 0))}</strong></div><div class="summary-chip is-warning">结果未知<strong>${number(summary.uncertain ?? 0)}</strong></div></div><div class="queue-foot"><span>反复重试未成功</span><strong>${number(summary.stuck ?? 0)}</strong></div></div></article>
      <article class="card"><div class="card__header"><div><h3>失败原因分布</h3><p>失败、重试与结果未知的错误码</p></div></div><div class="card__body"><div class="bar-list">${failureRows || '<div class="empty">窗口内没有失败投递</div>'}</div><div class="queue-foot"><span>队列积压（pending / processing / retryable）</span><strong>${number(queue.pending ?? 0)} / ${number(queue.processing ?? 0)} / ${number(queue.retryable ?? 0)}</strong></div></div></article>
    </section>
    <section class="card data-card"><div class="data-card__toolbar"><div class="toolbar"><div class="filter-tabs">${tabs}</div><label class="field">时间 <select id="delivery-range">${Object.entries(ranges).map(([value, label]) => `<option value="${value}" ${String(state.deliveryRange) === value ? 'selected' : ''}>${label}</option>`).join('')}</select></label><label class="field">idol <select id="delivery-idol"><option value="all">全部 idol</option>${state.idols.map((item) => `<option value="${safe(item.id)}" ${state.deliveryIdol === item.id ? 'selected' : ''}>${safe(item.name)}</option>`).join('')}</select></label></div><span class="result-count">${state.deliveries.length >= 200 ? '仅显示最近 200 条' : `${state.deliveries.length} 条投递`}</span></div><div class="table-wrap"><table><thead><tr><th>动态</th><th>用户</th><th>idol</th><th>状态</th><th>尝试次数</th><th>创建时间</th><th>结束时间</th><th>回访时间</th><th>操作</th></tr></thead><tbody>${rows || '<tr><td colspan="9"><div class="empty">当前条件下没有投递记录</div></td></tr>'}</tbody></table></div></section>`;
}

function deliveryDetail(key: string): void {
  const item = state.deliveries.find((delivery) => deliveryKey(delivery) === key);
  if (!item) return;
  openDrawer({ eyebrow: '投递详情', title: item.postTitle || item.postId, body: `<dl class="detail-list"><div><dt>用户标识</dt><dd>${safe(item.userId)}<br /><small>内部用户 ID，管理端不展示 OpenID</small></dd></div><div><dt>idol</dt><dd>${safe(item.idolName)}（${safe(item.idolId)}）</dd></div><div><dt>动态编号</dt><dd>${safe(item.postId)}</dd></div><div><dt>投递状态</dt><dd>${statusBadge(item.status)}</dd></div><div><dt>尝试次数</dt><dd>${item.attemptCount}</dd></div><div><dt>错误码</dt><dd>${safe(item.errorCode || '—')}</dd></div><div><dt>创建时间</dt><dd>${timeText(item.createdAt)}</dd></div><div><dt>最近尝试</dt><dd>${timeText(item.attemptedAt)}</dd></div><div><dt>结束时间</dt><dd>${timeText(item.finishedAt)}</dd></div><div><dt>下次重试</dt><dd>${timeText(item.nextAttemptAt)}</dd></div><div><dt>回访次数</dt><dd>${item.openCount}</dd></div></dl>` });
}

export const actions: Record<string, (id: string) => void> = {
  'delivery-detail': deliveryDetail,
};
