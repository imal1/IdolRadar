import { adminRequest, submitChange } from '../api';
import { state } from '../state';
import type { Idol } from '../types';
import { $, fieldValue, icon, number, openModal, pageHeading, safe, searchField, showToast, statusBadge, timeText } from '../ui';

export function render(): string {
  const filtered = state.idols.filter((item) => state.idolStatus === 'all' || item.enabled === (state.idolStatus === 'enabled'));
  const rows = filtered.map((item) => `
    <tr data-search="${safe(`${item.id} ${item.name} ${item.bio || ''}`)}">
      <td><div class="entity"><span class="entity__avatar">${safe(item.name.slice(0, 1))}</span><span><strong>${safe(item.name)}</strong><small>${safe(item.bio || item.id)}</small></span></div></td>
      <td>${statusBadge(item.enabled ? 'enabled' : 'disabled')}</td><td>${item.sourceCount}</td><td>${number(item.guardCount)}</td><td>v${item.version}</td><td>${timeText(item.updatedAt)}</td>
      <td><div class="actions"><button class="button button--small" data-action="edit-idol" data-id="${safe(item.id)}" type="button">编辑</button><button class="switch ${item.enabled ? 'is-on' : ''}" data-action="toggle-idol" data-id="${safe(item.id)}" type="button" aria-label="${item.enabled ? '停用' : '启用'}${safe(item.name)}"></button></div></td>
    </tr>`).join('');
  return `
    ${pageHeading('idol 管理', '维护可守护对象；停用不会删除历史动态、守护或投递记录。', `<button class="button button--primary" data-action="add-idol" type="button">${icon('plus')} 新增 idol</button>`)}
    <section class="card data-card">
      <div class="data-card__toolbar"><div class="toolbar">${searchField('idol-search', '搜索标识、名称或简介')}<label class="field">状态 <select id="idol-status"><option value="all">全部</option><option value="enabled" ${state.idolStatus === 'enabled' ? 'selected' : ''}>启用</option><option value="disabled" ${state.idolStatus === 'disabled' ? 'selected' : ''}>停用</option></select></label></div><span class="result-count" id="idol-count">共 ${filtered.length} 位</span></div>
      <div class="table-wrap"><table><thead><tr><th>idol</th><th>状态</th><th>动态源</th><th>守护人数</th><th>数据版本</th><th>最后更新</th><th>操作</th></tr></thead><tbody id="idol-table">${rows || '<tr><td colspan="7"><div class="empty">没有符合条件的 idol</div></td></tr>'}</tbody></table></div>
    </section>`;
}

function idolForm(item?: Idol): string {
  const creating = !item;
  return `<div class="form-grid" id="idol-form">
    ${creating ? '<label class="form-field"><span>idol 标识 *</span><input name="id" placeholder="例如 wang_yibo" /><p class="form-hint">小写字母、数字、下划线或中划线，创建后不可修改。</p></label>' : ''}
    <label class="form-field"><span>名称 *</span><input name="name" value="${safe(item?.name || '')}" placeholder="请输入 idol 名称" required /></label>
    <label class="form-field"><span>状态</span><select name="enabled"><option value="true" ${item?.enabled !== false ? 'selected' : ''}>启用</option><option value="false" ${item?.enabled === false ? 'selected' : ''}>停用</option></select></label>
    <label class="form-field form-field--wide"><span>简介</span><textarea name="bio" placeholder="简要描述身份或业务标签">${safe(item?.bio || '')}</textarea></label>
    <label class="form-field form-field--wide"><span>头像地址</span><input name="avatar" type="url" value="${safe(item?.avatar || '')}" placeholder="https://..." /></label>
  </div>`;
}

/**
 * 新增与编辑共用一个弹窗：没传 id 就是创建。
 *
 * <p>编辑走 PATCH 并带上 `version`，由后端做乐观锁校验——两个管理员同时改同一个 idol 时，
 * 后提交的一方会收到版本冲突而不是静默覆盖。校验不通过时返回 false 让弹窗留在原地。
 */
function editIdol(id?: string): void {
  const item = state.idols.find((idol) => idol.id === id);
  openModal({ title: item ? `编辑 ${item.name}` : '新增 idol', body: idolForm(item), confirm: item ? '保存修改' : '创建 idol', onConfirm: () => {
    const form = $('#idol-form');
    const name = fieldValue(form, 'name');
    if (!name) { showToast('请填写 idol 名称'); return false; }
    const payload = {
      name,
      bio: fieldValue(form, 'bio'),
      avatar: fieldValue(form, 'avatar'),
      enabled: fieldValue(form, 'enabled') === 'true',
    };
    if (item) {
      return submitChange(
        () => adminRequest(`/admin/v1/idols/${encodeURIComponent(item.id)}`, {
          method: 'PATCH',
          body: JSON.stringify({ ...payload, version: item.version }),
        }),
        'idol 信息已更新');
    }
    const newId = fieldValue(form, 'id');
    if (!newId) { showToast('请填写 idol 标识'); return false; }
    return submitChange(
      () => adminRequest('/admin/v1/idols', { method: 'POST', body: JSON.stringify({ id: newId, ...payload }) }),
      'idol 已创建');
  } });
}

function toggleIdol(id: string): void {
  const item = state.idols.find((idol) => idol.id === id);
  if (!item) return;
  const disabling = item.enabled;
  const body = disabling
    ? `<div class="impact-box"><strong>影响范围</strong><br />${item.sourceCount} 个动态源将停止抓取；${number(item.guardCount)} 位守护用户不再收到新推送。历史动态、守护关系和投递记录都会保留。</div>`
    : '<p>启用后，该 idol 的已启用来源会在下一轮调度恢复抓取。</p>';
  openModal({ eyebrow: '状态变更', title: `${disabling ? '停用' : '启用'} ${item.name}`, body, confirm: disabling ? '确认停用' : '确认启用', danger: disabling, onConfirm: () => submitChange(
    () => adminRequest(`/admin/v1/idols/${encodeURIComponent(item.id)}`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled: !item.enabled, version: item.version }),
    }),
    `${item.name} 已${disabling ? '停用' : '启用'}`) });
}

export const actions: Record<string, (id: string) => void> = {
  'add-idol': () => editIdol(),
  'edit-idol': (id) => editIdol(id),
  'toggle-idol': toggleIdol,
};
