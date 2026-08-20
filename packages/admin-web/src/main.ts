import './styles.css';

import { ADMIN_TOKEN_KEY, adminRequest, bindApiHandlers, errorMessage, requestReload } from './api';
import { state, type PageId } from './state';
import type { AdminProfile, CoreMetrics, DeliveryBoard, Idol, IdolRequest, LoginResult, Source, SourceSummary } from './types';
import { $, $$, icon, showToast } from './ui';

import * as audit from './pages/audit';
import * as dashboard from './pages/dashboard';
import * as deliveries from './pages/deliveries';
import * as idols from './pages/idols';
import * as requests from './pages/requests';
import * as sources from './pages/sources';

const pageMeta: Record<PageId, { label: string; icon: string; badge?: number }> = {
  dashboard: { label: '核心指标', icon: 'dashboard' },
  idols: { label: 'idol 管理', icon: 'idols' },
  sources: { label: '动态源', icon: 'sources' },
  deliveries: { label: '推送投递', icon: 'deliveries' },
  requests: { label: '申请审核', icon: 'requests', badge: 0 },
  audit: { label: '审计日志', icon: 'audit' },
};

const renderers: Record<PageId, () => string> = {
  dashboard: dashboard.render,
  idols: idols.render,
  sources: sources.render,
  deliveries: deliveries.render,
  requests: requests.render,
  audit: audit.render,
};

// 页面自带的操作按钮由各页面模块实现；main 只做分发，不重复实现表单与弹窗。
const pageActions: Record<string, (id: string) => void> = {
  ...idols.actions,
  ...sources.actions,
  ...deliveries.actions,
  ...requests.actions,
  ...audit.actions,
};

function isPageId(value: string): value is PageId {
  return value in renderers;
}

function renderNavigation(): void {
  $$<HTMLButtonElement>('.nav__item').forEach((button) => {
    const page = button.dataset.page as PageId;
    const meta = pageMeta[page];
    button.innerHTML = `${icon(meta.icon)}<span>${meta.label}</span>${meta.badge ? `<span class="nav__badge">${meta.badge}</span>` : ''}`;
    button.classList.toggle('is-active', page === state.page);
  });
}

function renderPage({ focus = true } = {}): void {
  renderNavigation();
  $('#page-title').textContent = pageMeta[state.page].label;
  $('#page-root').innerHTML = renderers[state.page]();
  if (focus) $('#page-root').focus({ preventScroll: true });
}

async function loadIdols(): Promise<void> {
  state.idols = (await adminRequest<{ idols: Idol[] }>('/admin/v1/idols')).idols;
}

async function loadSources(): Promise<void> {
  // 健康度筛选交给服务端：判定逻辑只有一份，筛选结果和列表展示不会互相矛盾。
  const query = state.sourceStatus === 'all' ? '' : `?health=${encodeURIComponent(state.sourceStatus)}`;
  const data = await adminRequest<{ sources: Source[]; summary: SourceSummary }>(`/admin/v1/sources${query}`);
  state.sources = data.sources;
  state.sourceSummary = data.summary;
}

async function loadRequests(): Promise<void> {
  const query = state.requestFilter === 'all' ? '' : `?status=${encodeURIComponent(state.requestFilter)}`;
  const data = await adminRequest<{ requests: IdolRequest[]; pendingCount: number }>(`/admin/v1/idol-requests${query}`);
  state.requests = data.requests;
  state.pendingCount = data.pendingCount;
  pageMeta.requests.badge = data.pendingCount;
}

/**
 * 拉取投递看板数据。
 *
 * <p>状态、时间区间、idol 全部作为查询参数下发：分布与积压必须按同一条件由服务端聚合，
 * 前端只拿到最近 200 条明细，本地过滤会得出与汇总互相矛盾的数字。
 *
 * @param scoped 首页概览固定看全量近 24 小时，不能跟着投递页的筛选走，否则卡片标题与数字不符
 */
async function loadDeliveries({ scoped = true } = {}): Promise<void> {
  const query = new URLSearchParams({ rangeHours: String(scoped ? state.deliveryRange : 24) });
  if (scoped && state.deliveryFilter !== 'all') query.set('status', state.deliveryFilter);
  if (scoped && state.deliveryIdol !== 'all') query.set('idolId', state.deliveryIdol);
  const data = await adminRequest<DeliveryBoard>(`/admin/v1/deliveries?${query}`);
  state.deliveries = data.deliveries;
  state.deliverySummary = data.summary;
  state.deliveryFailures = data.failures;
  state.deliveryQueue = data.queue;
}

async function loadMetrics(): Promise<void> {
  state.metrics = await adminRequest<CoreMetrics>(`/admin/v1/metrics?rangeDays=${state.metricsRange}`);
}

// 新建来源要选所属 idol，所以来源页同时需要 idol 列表；投递页的 idol 筛选同理。
const loaders: Partial<Record<PageId, () => Promise<unknown>>> = {
  dashboard: () => Promise.all([loadMetrics(), loadSources(), loadRequests(), loadDeliveries({ scoped: false })]),
  idols: loadIdols,
  sources: () => Promise.all([loadSources(), loadIdols()]),
  deliveries: () => Promise.all([loadDeliveries(), loadIdols()]),
  requests: loadRequests,
};

/** 拉取当前页数据并重绘；失败时保留上一次结果并提示，不把页面清空。 */
async function reload(): Promise<void> {
  const load = loaders[state.page];
  if (load) {
    try {
      await load();
    } catch (error) {
      showToast(errorMessage(error, '管理后台暂时不可用'));
    }
  }
  renderPage({ focus: false });
}

function navigate(page: string): void {
  if (!isPageId(page)) return;
  state.page = page;
  renderPage();
  window.scrollTo(0, 0);
  $('#sidebar').classList.remove('is-open');
  history.replaceState(null, '', `#${page}`);
  void reload();
}

// 原型只过滤当前页面已渲染的演示数据；真实后台应由服务端分页和权限过滤。
function filterVisibleRows(input: HTMLInputElement, table: HTMLElement, counter: HTMLElement, suffix: string): void {
  const keyword = input.value.trim().toLowerCase();
  let visible = 0;
  $$<HTMLTableRowElement>('tr[data-search]', table).forEach((row) => {
    const matched = !keyword || (row.dataset.search ?? '').toLowerCase().includes(keyword);
    row.hidden = !matched;
    if (matched) visible += 1;
  });
  if (counter) counter.textContent = `${visible} ${suffix}`;
}

function handlePageClick(event: Event): void {
  const button = (event.target as HTMLElement).closest('button');
  if (!button) return;
  if (button.dataset.pageJump) return navigate(button.dataset.pageJump);
  if (button.dataset.toast) return showToast(button.dataset.toast);
  if (button.dataset.deliveryFilter) { state.deliveryFilter = button.dataset.deliveryFilter; return requestReload(); }
  if (button.dataset.requestFilter) { state.requestFilter = button.dataset.requestFilter; return requestReload(); }
  const action = button.dataset.action;
  if (!action) return;
  if (action === 'refresh-deliveries') return requestReload();
  if (action === 'refresh-dashboard') {
    requestReload();
    $('#update-time').textContent = `数据更新于 ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`;
    return;
  }
  pageActions[action]?.(button.dataset.id ?? '');
}

function handlePageChange(event: Event): void {
  const target = event.target as HTMLSelectElement;
  if (target.id === 'idol-status') { state.idolStatus = target.value; renderPage({ focus: false }); }
  if (target.id === 'source-status') { state.sourceStatus = target.value; requestReload(); }
  if (target.id === 'audit-result') { state.auditResult = target.value; renderPage({ focus: false }); }
  if (target.id === 'delivery-range') { state.deliveryRange = Number(target.value); requestReload(); }
  if (target.id === 'delivery-idol') { state.deliveryIdol = target.value; requestReload(); }
  if (target.id === 'dashboard-range') { state.metricsRange = Number(target.value); requestReload(); }
}

function handlePageInput(event: Event): void {
  const filters: Record<string, [string, string, string]> = {
    'idol-search': ['#idol-table', '#idol-count', '位'],
    'source-search': ['#source-table', '#source-count', '个来源'],
    'request-search': ['#request-table', '#request-count', '条申请'],
    'audit-search': ['#audit-table', '#audit-count', '条记录'],
  };
  const input = event.target as HTMLInputElement;
  const target = filters[input.id];
  if (target) filterVisibleRows(input, $(target[0]), $(target[1]), target[2]);
}

function toggleAccountMenu(): void {
  const existing = $('.account-menu');
  if (existing) return existing.remove();
  const menu = document.createElement('div');
  menu.className = 'account-menu';
  menu.innerHTML = `<button type="button" data-account-action="profile">${icon('profile')} 当前管理员</button><button type="button" data-account-action="logout">${icon('logout')} 退出登录</button>`;
  menu.addEventListener('click', (event) => {
    const action = (event.target as HTMLElement).closest('button')?.dataset.accountAction;
    if (action === 'profile') showToast('管理员资料页不在本轮业务范围内');
    if (action === 'logout') void logout();
    menu.remove();
  });
  document.body.append(menu);
}

function showApplication(admin: AdminProfile): void {
  $('#login-screen').classList.add('is-hidden');
  $('#app-shell').classList.remove('is-hidden');
  $$('[data-admin-name]').forEach((element) => { element.textContent = admin.username; });
  const hashPage = location.hash.slice(1);
  state.page = isPageId(hashPage) ? hashPage : 'dashboard';
  renderPage();
  void reload();
}

function showLogin(message = '', error = false): void {
  $('#app-shell').classList.add('is-hidden');
  $('#login-screen').classList.remove('is-hidden');
  const status = $('#login-message');
  status.textContent = message;
  status.classList.toggle('is-error', error);
  history.replaceState(null, '', location.pathname);
}

async function login(): Promise<void> {
  const form = $('#login-form');
  const button = $<HTMLButtonElement>('button[type="submit"]', form);
  const status = $('#login-message');
  button.disabled = true;
  status.textContent = '正在验证…';
  status.classList.remove('is-error');
  try {
    const response = await fetch('/admin/v1/auth/login', {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username: $<HTMLInputElement>('#login-account').value.trim(),
        password: $<HTMLInputElement>('#login-password').value,
      }),
    });
    const body = (await response.json().catch(() => null)) as { ok?: boolean; data?: LoginResult; error?: { message?: string } } | null;
    if (!response.ok || !body?.ok || !body.data) {
      throw new Error(body?.error?.message || '登录失败');
    }
    // sessionStorage 限制 token 生命周期到当前标签页；不使用长期 localStorage。
    sessionStorage.setItem(ADMIN_TOKEN_KEY, body.data.token);
    $<HTMLInputElement>('#login-password').value = '';
    showApplication(body.data.admin);
  } catch (error) {
    status.textContent = errorMessage(error, '登录失败');
    status.classList.add('is-error');
  } finally {
    button.disabled = false;
  }
}

async function logout(): Promise<void> {
  try {
    await adminRequest('/admin/v1/auth/logout', { method: 'POST' });
  } catch {
    // 即使网络失败也删除本地 token；服务端会话仍受短 TTL 限制，可由其他管理员吊销。
  } finally {
    sessionStorage.removeItem(ADMIN_TOKEN_KEY);
    showLogin('已退出登录。', false);
  }
}

async function restoreSession(): Promise<void> {
  if (!sessionStorage.getItem(ADMIN_TOKEN_KEY)) return;
  try {
    showApplication(await adminRequest<AdminProfile>('/admin/v1/me'));
  } catch (error) {
    if (sessionStorage.getItem(ADMIN_TOKEN_KEY)) {
      showLogin(errorMessage(error, '管理后台暂时不可用'), true);
    }
  }
}

async function initialize(): Promise<void> {
  $('#toggle-password').innerHTML = icon('eye');
  $('#mobile-menu').innerHTML = icon('menu');
  $('.notification-button').innerHTML = icon('bell');
  $('#update-time').textContent = `数据更新于 ${new Date().toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })}`;
  $('#login-form').addEventListener('submit', (event) => { event.preventDefault(); void login(); });
  $('#toggle-password').addEventListener('click', () => {
    const input = $<HTMLInputElement>('#login-password');
    const visible = input.type === 'text';
    input.type = visible ? 'password' : 'text';
    $('#toggle-password').innerHTML = icon(visible ? 'eye' : 'eyeOff');
    $('#toggle-password').setAttribute('aria-label', visible ? '显示密码' : '隐藏密码');
  });
  $('#mobile-menu').addEventListener('click', () => $('#sidebar').classList.toggle('is-open'));
  $('#account-trigger').addEventListener('click', toggleAccountMenu);
  $('#sidebar-account').addEventListener('click', toggleAccountMenu);
  $('#page-root').addEventListener('click', handlePageClick);
  $('#page-root').addEventListener('change', handlePageChange);
  $('#page-root').addEventListener('input', handlePageInput);
  $('.nav').addEventListener('click', (event) => {
    const button = (event.target as HTMLElement).closest<HTMLElement>('[data-page]');
    if (button?.dataset.page) navigate(button.dataset.page);
  });
  document.addEventListener('click', (event) => {
    const toastButton = (event.target as HTMLElement).closest<HTMLElement>('[data-toast]');
    if (toastButton && !$('#page-root').contains(toastButton)) showToast(toastButton.dataset.toast as string);
  });
  window.addEventListener('hashchange', () => {
    const page = location.hash.slice(1);
    if (isPageId(page) && page !== state.page) navigate(page);
  });
  bindApiHandlers({ reload, unauthorized: (message) => showLogin(message, true) });
  await restoreSession();
}

void initialize();
