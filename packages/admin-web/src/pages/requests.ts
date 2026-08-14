import { adminRequest, submitChange } from '../api';
import { state } from '../state';
import { $, fieldValue, number, openDrawer, openModal, pageHeading, safe, searchField, showToast, statusBadge, timeText } from '../ui';

export function render(): string {
  const labels: Record<string, string> = { pending: '待审核', approved: '已通过', rejected: '已驳回', all: '全部' };
  const tabs = Object.entries(labels).map(([value, label]) => `<button class="${state.requestFilter === value ? 'is-active' : ''}" data-request-filter="${value}" type="button">${label}</button>`).join('');
  const top = Math.max(1, ...state.requests.map((item) => item.supporterCount));
  const rows = state.requests.map((item) => `<tr data-search="${safe(`${item.displayName} ${item.note || ''}`)}"><td><strong>${safe(item.displayName)}</strong><div class="request-user">合并键 ${safe(item.normalizedName)}</div></td><td><strong>${number(item.supporterCount)}</strong><div class="progress"><span style="width:${Math.round((item.supporterCount / top) * 100)}%"></span></div></td><td><div class="request-note">${safe(item.note || '未填写补充说明')}</div></td><td>${timeText(item.createdAt)}</td><td>${statusBadge(item.status)}</td><td>${item.status === 'pending' ? `<div class="actions"><button class="button button--small" data-action="approve-request" data-id="${safe(item.id)}" type="button">通过</button><button class="button button--small button--danger" data-action="reject-request" data-id="${safe(item.id)}" type="button">驳回</button></div>` : `<button class="button button--small button--neutral" data-action="request-detail" data-id="${safe(item.id)}" type="button">查看结果</button>`}</td></tr>`).join('');
  return `
    ${pageHeading('idol 申请审核', '优先处理支持人数高的申请；通过时创建或关联正式 idol。')}
    <section class="card data-card"><div class="data-card__toolbar"><div class="toolbar"><div class="filter-tabs">${tabs}</div>${searchField('request-search', '搜索申请名称')}</div><span class="result-count" id="request-count">${state.requests.length} 条申请</span></div><div class="table-wrap"><table><thead><tr><th>申请 idol</th><th>支持人数</th><th>用户说明</th><th>申请时间</th><th>状态</th><th>操作</th></tr></thead><tbody id="request-table">${rows || '<tr><td colspan="6"><div class="empty">当前状态下没有申请</div></td></tr>'}</tbody></table></div></section>`;
}

/**
 * 审核一条 idol 申请：通过与驳回是两个后端接口，表单与必填项也不同。
 *
 * <p>通过时 `idolId` 必填——填已有标识即关联该 idol，填新标识则由后端在同一事务里创建正式 idol，
 * 所以前端只提交标识，不预先创建。驳回时原因必填，会展示给申请用户。
 */
function reviewRequest(id: string, approved: boolean): void {
  const item = state.requests.find((request) => request.id === id);
  if (!item) return;
  const body = approved
    ? `<div class="form-grid" id="review-form">
        <label class="form-field form-field--wide"><span>正式 idol 标识 *</span><input name="idolId" list="idol-options" placeholder="例如 wang_yibo" /><datalist id="idol-options">${state.idols.map((idol) => `<option value="${safe(idol.id)}">${safe(idol.name)}</option>`).join('')}</datalist><p class="form-hint">填写已有标识即关联该 idol，填写新标识则同事务创建正式 idol。</p></label>
        <label class="form-field form-field--wide"><span>正式名称</span><input name="idolName" value="${safe(item.displayName)}" /></label>
        <label class="form-field form-field--wide"><span>审核备注</span><textarea name="note">申请通过，进入正式来源配置流程。</textarea></label>
        <div class="form-field form-field--wide"><div class="impact-box">通过后仍需配置至少一个可用动态源；未配置来源前，小程序不会展示为可守护对象。</div></div>
      </div>`
    : '<div class="form-grid" id="review-form"><label class="form-field form-field--wide"><span>驳回原因 *</span><textarea name="note" placeholder="该原因会展示给申请用户"></textarea></label></div>';
  openModal({ eyebrow: '申请审核', title: `${approved ? '通过' : '驳回'}「${item.displayName}」`, body, confirm: approved ? '确认通过' : '确认驳回', danger: !approved, onConfirm: () => {
    const form = $('#review-form');
    const note = fieldValue(form, 'note');
    if (!approved) {
      if (!note) { showToast('请填写驳回原因'); return false; }
      return submitChange(
        () => adminRequest(`/admin/v1/idol-requests/${encodeURIComponent(item.id)}/reject`, {
          method: 'POST',
          body: JSON.stringify({ reviewNote: note }),
        }),
        '申请已驳回');
    }
    const idolId = fieldValue(form, 'idolId');
    if (!idolId) { showToast('请填写正式 idol 标识'); return false; }
    return submitChange(
      () => adminRequest(`/admin/v1/idol-requests/${encodeURIComponent(item.id)}/approve`, {
        method: 'POST',
        body: JSON.stringify({ idolId, idolName: fieldValue(form, 'idolName'), reviewNote: note }),
      }),
      '申请已通过');
  } });
}

function requestDetail(id: string): void {
  const item = state.requests.find((request) => request.id === id);
  if (!item) return;
  openDrawer({ eyebrow: '审核结果', title: item.displayName, body: `<dl class="detail-list"><div><dt>当前状态</dt><dd>${statusBadge(item.status)}</dd></div><div><dt>支持人数</dt><dd>${number(item.supporterCount)} 人</dd></div><div><dt>用户说明</dt><dd>${safe(item.note || '未填写')}</dd></div><div><dt>审核人</dt><dd>${safe(item.reviewer || '—')}</dd></div><div><dt>审核时间</dt><dd>${timeText(item.reviewedAt)}</dd></div><div><dt>审核备注</dt><dd>${safe(item.reviewNote || '—')}</dd></div><div><dt>关联 idol</dt><dd>${safe(item.approvedIdolId || '—')}</dd></div></dl>` });
}

export const actions: Record<string, (id: string) => void> = {
  'approve-request': (id) => reviewRequest(id, true),
  'reject-request': (id) => reviewRequest(id, false),
  'request-detail': requestDetail,
};
