import { state } from '../state';
import { openDrawer, pageHeading, pagination, safe, searchField, statusBadge } from '../ui';

/** 本页仍渲染 state.audits 里的演示数据；接真实审计接口是独立 issue。 */
export function render(): string {
  const filtered = state.audits.filter((item) => state.auditResult === 'all' || item.result === state.auditResult);
  const rows = filtered.map((item) => `<tr data-search="${safe(`${item.operator} ${item.action} ${item.resource} ${item.requestId} ${item.summary}`)}"><td>${safe(item.time)}</td><td>${safe(item.operator)}</td><td><span class="badge badge--violet">${safe(item.action)}</span></td><td><span class="audit-resource">${safe(item.resource)}</span></td><td>${statusBadge(item.result)}</td><td>${safe(item.requestId)}</td><td>${safe(item.summary)}</td><td><button class="button button--small button--neutral" data-action="audit-detail" data-id="${item.id}" type="button">详情</button></td></tr>`).join('');
  return `
    ${pageHeading('管理审计日志', '记录管理端写操作的操作者、资源、结果和摘要；不保存密码、token、OpenID 或服务密钥。', '<button class="button button--neutral" data-toast="演示日志已导出为 CSV" type="button">导出当前结果</button>')}
    <section class="card data-card"><div class="data-card__toolbar"><div class="toolbar">${searchField('audit-search', '搜索操作、资源或 request ID')}<label class="field">结果 <select id="audit-result"><option value="all">全部</option><option value="success" ${state.auditResult === 'success' ? 'selected' : ''}>成功</option><option value="failed" ${state.auditResult === 'failed' ? 'selected' : ''}>失败</option></select></label><label class="field">时间 <select><option>今天</option><option>最近 7 天</option><option>最近 30 天</option></select></label></div><span class="result-count" id="audit-count">${filtered.length} 条记录</span></div><div class="table-wrap"><table><thead><tr><th>时间</th><th>操作者</th><th>操作类型</th><th>业务资源</th><th>结果</th><th>request ID</th><th>业务摘要</th><th>操作</th></tr></thead><tbody id="audit-table">${rows}</tbody></table></div>${pagination()}</section>`;
}

function auditDetail(id: string): void {
  const item = state.audits.find((audit) => audit.id === id);
  if (!item) return;
  openDrawer({ eyebrow: '审计详情', title: item.id, body: `<dl class="detail-list"><div><dt>操作时间</dt><dd>${safe(item.time)}</dd></div><div><dt>操作者</dt><dd>${safe(item.operator)}</dd></div><div><dt>操作类型</dt><dd>${safe(item.action)}</dd></div><div><dt>业务资源</dt><dd>${safe(item.resource)}</dd></div><div><dt>执行结果</dt><dd>${statusBadge(item.result)}</dd></div><div><dt>request ID</dt><dd>${safe(item.requestId)}</dd></div><div><dt>业务摘要</dt><dd>${safe(item.summary)}</dd></div></dl><div class="code-block"><strong>修改前</strong><br />${safe(item.before)}<br /><br /><strong>修改后</strong><br />${safe(item.after)}</div>` });
}

export const actions: Record<string, (id: string) => void> = {
  'audit-detail': auditDetail,
};
