import { state } from '../state';
import { requestReload } from '../api';
import type { AuditEntry } from '../types';
import { openDrawer, pageHeading, safe, searchField, statusBadge, timeText } from '../ui';

export function detailBody(item: AuditEntry): string {
  return `<dl class="detail-list"><div><dt>修改前</dt><dd>${safe(item.beforeSummary || '—')}</dd></div><div><dt>修改后</dt><dd>${safe(item.afterSummary || '—')}</dd></div><div><dt>request ID</dt><dd>${safe(item.requestId || '—')}</dd></div><div><dt>HTTP 状态</dt><dd>${safe(item.httpStatus ?? '—')}</dd></div></dl>`;
}

/** 生成服务端查询参数；筛选和游标都不在浏览器本地伪造。 */
export function queryParams(): URLSearchParams {
  const query = new URLSearchParams({ rangeHours: String(state.auditRangeHours) });
  if (state.auditResult !== 'all') query.set('result', state.auditResult);
  if (state.auditSearch.trim()) query.set('search', state.auditSearch.trim());
  const cursor = state.auditCursors.at(-1);
  if (cursor) query.set('cursor', cursor);
  return query;
}

/** 回车或失焦确认搜索；相同条件不重复请求，避免回车后紧接着 blur 触发两次加载。 */
export function applySearch(value: string): void {
  const search = value.trim();
  if (search === state.auditSearch) return;
  state.auditSearch = search;
  state.auditCursors = [null];
  state.auditNextCursor = null;
  requestReload();
}

export function render(): string {
  const rows = state.audits.length
    ? state.audits.map((item) => {
      const resource = item.resourceId ? `${item.resourceType} · ${item.resourceId}` : item.resourceType;
      return `<tr><td>${safe(timeText(item.createdAt))}</td><td>${safe(item.operator)}</td><td><span class="badge badge--violet">${safe(item.action)}</span></td><td><span class="audit-resource">${safe(resource)}</span></td><td>${statusBadge(item.succeeded ? 'success' : 'failed')}</td><td>${safe(item.requestId || '—')}</td><td>${safe(item.httpStatus ?? '—')}</td><td><button class="button button--small button--neutral" data-action="audit-detail" data-id="${safe(item.id)}" type="button">查看</button></td></tr>`;
    }).join('')
    : '<tr><td colspan="8" class="empty-cell">暂无审计记录</td></tr>';
  const previousDisabled = state.auditCursors.length === 1 ? ' disabled' : '';
  const nextDisabled = state.auditNextCursor ? '' : ' disabled';
  return `
    ${pageHeading('管理审计日志', '记录管理端写操作的操作者、资源和结果；不返回原始详情、密码、token、OpenID 或服务密钥。')}
    <section class="card data-card"><div class="data-card__toolbar"><div class="toolbar">${searchField('audit-search', '搜索操作者或 request ID（回车确认）', state.auditSearch)}<label class="field">结果 <select id="audit-result"><option value="all">全部</option><option value="success" ${state.auditResult === 'success' ? 'selected' : ''}>成功</option><option value="failed" ${state.auditResult === 'failed' ? 'selected' : ''}>失败</option></select></label><label class="field">时间 <select id="audit-range"><option value="24" ${state.auditRangeHours === 24 ? 'selected' : ''}>最近 24 小时</option><option value="168" ${state.auditRangeHours === 168 ? 'selected' : ''}>最近 7 天</option><option value="720" ${state.auditRangeHours === 720 ? 'selected' : ''}>最近 30 天</option></select></label></div><span class="result-count" id="audit-count">本页 ${state.audits.length} 条</span></div><div class="table-wrap"><table><thead><tr><th>时间</th><th>操作者</th><th>操作类型</th><th>业务资源</th><th>结果</th><th>request ID</th><th>HTTP 状态</th><th>详情</th></tr></thead><tbody id="audit-table">${rows}</tbody></table></div><div class="pagination"><button data-action="audit-previous"${previousDisabled} type="button" aria-label="上一页">‹</button><span>第 ${state.auditCursors.length} 页</span><button data-action="audit-next"${nextDisabled} type="button" aria-label="下一页">›</button></div></section>`;
}

function showDetail(id: string): void {
  const item = state.audits.find((audit) => audit.id === id);
  if (!item) return;
  openDrawer({ eyebrow: '审计详情', title: item.action, body: detailBody(item) });
}

function nextPage(): void {
  if (!state.auditNextCursor) return;
  state.auditCursors = [...state.auditCursors, state.auditNextCursor];
  state.auditNextCursor = null;
  requestReload();
}

function previousPage(): void {
  if (state.auditCursors.length === 1) return;
  state.auditCursors = state.auditCursors.slice(0, -1);
  state.auditNextCursor = null;
  requestReload();
}

export const actions: Record<string, (id: string) => void> = {
  'audit-detail': showDetail,
  'audit-next': nextPage,
  'audit-previous': previousPage,
};
