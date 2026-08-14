import { adminRequest, errorMessage, submitChange } from '../api';
import { state } from '../state';
import type { Source, VerifyResult } from '../types';
import { $, fieldValue, icon, openModal, pageHeading, safe, searchField, showToast, sourceStatusBadge, timeText } from '../ui';

/** 看板首页只需一句结论：连续失败次数比状态词更能说明该不该现在去修。 */
export function healthStatusText(item: Source): string {
  if (item.health === 'failed') return `<span class="status status--danger">连续失败 ${item.consecutiveFailures} 次</span>`;
  if (item.health === 'stale') return '<span class="status status--danger">超过 24 小时未成功</span>';
  if (item.health === 'waiting') return '<span class="status status--muted">待首次抓取</span>';
  if (item.health === 'disabled') return '<span class="status status--muted">已停用</span>';
  return '<span class="status status--success">正常</span>';
}

export function render(): string {
  const summary = state.sourceSummary;
  const rows = state.sources.map((item) => `
    <tr data-search="${safe(`${item.idolName} ${item.displayName} ${item.channel}`)}">
      <td><div class="entity"><span class="entity__avatar">${safe(item.idolName.slice(0, 1))}</span><span><strong>${safe(item.displayName)}</strong><small>${safe(item.idolName)} · ${safe(item.channel)}</small></span></div></td>
      <td><span class="url" title="${safe(item.rssUrl)}">${safe(item.rssUrl)}</span></td><td>${sourceStatusBadge(item.health)}</td><td>${timeText(item.lastFetchAt)}</td><td>${timeText(item.lastSuccessAt)}</td><td>${item.lastFetchItemCount} / ${item.lastFetchNewCount}</td><td>${item.consecutiveFailures ? `<span class="failure-count">${item.consecutiveFailures} 次</span>` : '0'}${item.lastFetchErrorCode ? `<small>${safe(item.lastFetchErrorCode)}</small>` : ''}</td>
      <td><div class="actions"><button class="button button--small" data-action="manual-fetch" data-id="${safe(item.id)}" type="button">抓取验证</button><button class="button button--small button--neutral" data-action="edit-source" data-id="${safe(item.id)}" type="button">编辑</button><button class="switch ${item.enabled ? 'is-on' : ''}" data-action="toggle-source" data-id="${safe(item.id)}" type="button" aria-label="${item.enabled ? '停用' : '启用'}${safe(item.displayName)}"></button></div></td>
    </tr>`).join('');
  return `
    ${pageHeading('动态源与健康度', '统一维护 RSS 地址、启停状态和最新抓取健康度；手动验证只做校验，不入库、不推送。', `<button class="button button--primary" data-action="add-source" type="button">${icon('plus')} 新增动态源</button>`)}
    <section class="source-overview"><article class="card mini-stat"><span>正常来源</span><strong>${summary.healthy ?? 0}</strong></article><article class="card mini-stat"><span>抓取失败</span><strong style="color:var(--red)">${summary.failed ?? 0}</strong></article><article class="card mini-stat"><span>超过 24h 未成功</span><strong style="color:var(--red)">${summary.stale ?? 0}</strong></article><article class="card mini-stat"><span>待首次抓取</span><strong>${summary.waiting ?? 0}</strong></article></section>
    <section class="card data-card">
      <div class="data-card__toolbar"><div class="toolbar">${searchField('source-search', '搜索 idol 或来源名称')}<label class="field">健康状态 <select id="source-status"><option value="all">全部</option><option value="healthy" ${state.sourceStatus === 'healthy' ? 'selected' : ''}>正常</option><option value="failed" ${state.sourceStatus === 'failed' ? 'selected' : ''}>抓取失败</option><option value="stale" ${state.sourceStatus === 'stale' ? 'selected' : ''}>长期未成功</option><option value="waiting" ${state.sourceStatus === 'waiting' ? 'selected' : ''}>待抓取</option><option value="disabled" ${state.sourceStatus === 'disabled' ? 'selected' : ''}>停用</option></select></label></div><span class="result-count" id="source-count">共 ${state.sources.length} 个来源</span></div>
      <div class="table-wrap"><table><thead><tr><th>来源</th><th>RSS 地址（仅管理端）</th><th>健康状态</th><th>最近抓取</th><th>最近成功</th><th>解析 / 新增</th><th>连续失败</th><th>操作</th></tr></thead><tbody id="source-table">${rows || '<tr><td colspan="8"><div class="empty">没有符合条件的来源</div></td></tr>'}</tbody></table></div>
    </section>`;
}

function sourceForm(item?: Source): string {
  const creating = !item;
  return `<div class="form-grid" id="source-form">
    ${creating ? '<label class="form-field"><span>来源标识 *</span><input name="id" placeholder="例如 wang_yibo_weibo" /></label>' : ''}
    ${creating ? `<label class="form-field"><span>所属 idol *</span><select name="idolId">${state.idols.map((idol) => `<option value="${safe(idol.id)}">${safe(idol.name)}</option>`).join('')}</select></label>` : ''}
    <label class="form-field"><span>渠道 *</span><input name="channel" value="${safe(item?.channel || '微博')}" placeholder="例如 微博" /></label>
    <label class="form-field"><span>状态</span><select name="enabled"><option value="true" ${item?.enabled !== false ? 'selected' : ''}>启用</option><option value="false" ${item?.enabled === false ? 'selected' : ''}>停用</option></select></label>
    <label class="form-field form-field--wide"><span>展示名称 *</span><input name="displayName" value="${safe(item?.displayName || '')}" placeholder="例如：王一博工作室 · 微博" /></label>
    <label class="form-field form-field--wide"><span>RSS 地址 *</span><input name="rssUrl" type="url" value="${safe(item?.rssUrl || '')}" placeholder="https://rss.example.com/..." /><p class="form-hint">保存前由服务端执行协议、内网目标与 DNS 解析安全校验，校验不通过不会入库。</p></label>
  </div>`;
}

function editSource(id?: string): void {
  const item = state.sources.find((source) => source.id === id);
  if (!item && state.idols.length === 0) { showToast('请先创建至少一个 idol'); return; }
  openModal({ title: item ? `编辑 ${item.displayName}` : '新增动态源', body: sourceForm(item), confirm: item ? '保存并校验' : '创建并校验', onConfirm: () => {
    const form = $('#source-form');
    const displayName = fieldValue(form, 'displayName');
    const rssUrl = fieldValue(form, 'rssUrl');
    if (!displayName || !rssUrl) { showToast('请填写展示名称和 RSS 地址'); return false; }
    const payload = {
      rssUrl,
      displayName,
      channel: fieldValue(form, 'channel') || 'RSS',
      enabled: fieldValue(form, 'enabled') === 'true',
    };
    if (item) {
      return submitChange(
        () => adminRequest(`/admin/v1/sources/${encodeURIComponent(item.id)}`, {
          method: 'PATCH',
          body: JSON.stringify({ ...payload, version: item.version }),
        }),
        '动态源已保存，安全校验通过');
    }
    const newId = fieldValue(form, 'id');
    if (!newId) { showToast('请填写来源标识'); return false; }
    return submitChange(
      () => adminRequest('/admin/v1/sources', {
        method: 'POST',
        body: JSON.stringify({ id: newId, idolId: fieldValue(form, 'idolId'), ...payload }),
      }),
      '动态源已创建，安全校验通过');
  } });
}

function toggleSource(id: string): void {
  const item = state.sources.find((source) => source.id === id);
  if (!item) return;
  void submitChange(
    () => adminRequest(`/admin/v1/sources/${encodeURIComponent(item.id)}`, {
      method: 'PATCH',
      body: JSON.stringify({ enabled: !item.enabled, version: item.version }),
    }),
    `${item.displayName} 已${item.enabled ? '停用' : '启用'}`);
}

function manualFetch(id: string): void {
  const item = state.sources.find((source) => source.id === id);
  if (!item) return;
  const cell = (value: string | number, label: string) => `<div><strong>${safe(value)}</strong><span>${label}</span></div>`;
  const result = (parsed: string | number, added: string | number, status: string) => `<div class="fetch-result">${cell(parsed, '解析数量')}${cell(added, '新增数量')}${cell(status, '执行状态')}</div>`;
  openModal({ eyebrow: '抓取验证', title: item.displayName, body: `<div class="impact-box">本次操作复用定时抓取的下载与解析逻辑，但只读：不写入动态，也不创建推送任务。「新增数量」是若入库会新增的条数。</div>${result('—', '—', '等待')}<div id="fetch-samples"></div>`, confirm: '开始抓取', onConfirm: async () => {
    showToast('正在抓取并校验来源…');
    let data: VerifyResult;
    try {
      data = await adminRequest<VerifyResult>(`/admin/v1/sources/${encodeURIComponent(item.id)}/verify`, { method: 'POST' });
    } catch (error) {
      showToast(errorMessage(error, '抓取验证失败'));
      return false;
    }
    $('.fetch-result').outerHTML = result(data.itemCount, data.newCount, data.ok ? '成功' : safe(data.errorCode));
    $('#fetch-samples').innerHTML = data.samples?.length
      ? `<ul class="health-list">${data.samples.map((sample) => `<li><span>${safe(sample.title)}</span><span class="status ${sample.known ? 'status--muted' : 'status--success'}">${sample.known ? '已入库' : '将新增'}</span></li>`).join('')}</ul>`
      : '';
    showToast(data.ok ? `解析 ${data.itemCount} 条，其中 ${data.newCount} 条未入库；未产生推送` : `抓取失败：${data.errorCode}`);
    // 只读操作不改变来源状态，保持弹窗打开让管理员看完结果再关。
    return false;
  } });
}

export const actions: Record<string, (id: string) => void> = {
  'add-source': () => editSource(),
  'edit-source': (id) => editSource(id),
  'toggle-source': toggleSource,
  'manual-fetch': manualFetch,
};
