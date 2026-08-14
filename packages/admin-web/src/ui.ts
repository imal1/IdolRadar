/** 展示层原语：DOM 查询、转义、格式化、徽标与弹窗，不依赖任何页面或接口。 */

const icons: Record<string, string> = {
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

const escapes: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' };

/** 所有插入模板的动态文本都必须过这里；管理端数据里有用户提交的申请名称与备注。 */
export function safe(value: unknown): string {
  return String(value ?? '').replace(/[&<>'"]/g, (char) => escapes[char] as string);
}

export function icon(name: string): string {
  return `<svg class="icon" viewBox="0 0 24 24" aria-hidden="true">${icons[name] ?? icons.dashboard}</svg>`;
}

export const $ = <T extends Element = HTMLElement>(selector: string, root: ParentNode = document): T =>
  root.querySelector(selector) as T;

export const $$ = <T extends Element = HTMLElement>(selector: string, root: ParentNode = document): T[] =>
  [...root.querySelectorAll(selector)] as T[];

export const number = (value: number): string => new Intl.NumberFormat('zh-CN').format(value);

// 时间一律由服务端以 ISO 返回，展示层再本地化；相对时间让「多久没成功」一眼可读。
export function timeText(value: string | null | undefined): string {
  if (!value) return '—';
  const time = new Date(value).getTime();
  if (Number.isNaN(time)) return '—';
  const elapsed = Date.now() - time;
  if (elapsed < 60_000) return '刚刚';
  if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)} 分钟前`;
  if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)} 小时前`;
  return new Date(time).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' });
}

export function statusBadge(status: string): string {
  const map: Record<string, [string, string]> = {
    enabled: ['启用', 'badge--success'], disabled: ['停用', 'badge--neutral'], healthy: ['正常', 'badge--success'], waiting: ['待抓取', 'badge--violet'], stale: ['长期未成功', 'badge--warning'],
    success: ['成功', 'badge--success'], failed: ['失败', 'badge--warning'], retry: ['重试中', 'badge--warning'], queued: ['待发送', 'badge--violet'], pending: ['待审核', 'badge--warning'], approved: ['已通过', 'badge--success'], rejected: ['已驳回', 'badge--neutral'],
    // 投递账本的持久化状态，与后端 ck_idr_notification_delivery_status 一一对应。
    sent: ['成功', 'badge--success'], sending: ['发送中', 'badge--violet'], reserved: ['已预留额度', 'badge--violet'], retryable: ['重试中', 'badge--warning'], uncertain: ['结果未知', 'badge--warning'],
  };
  const [label, className] = map[status] ?? [status, 'badge--neutral'];
  return `<span class="badge ${className}">${safe(label)}</span>`;
}

export function sourceStatusBadge(status: string): string {
  return status === 'failed' ? '<span class="badge badge--warning">异常</span>' : statusBadge(status);
}

export function pageHeading(title: string, description: string, actions = ''): string {
  return `<div class="page-heading"><div><h2>${title}</h2><p>${description}</p></div><div class="toolbar">${actions}</div></div>`;
}

export function searchField(id: string, placeholder: string): string {
  return `<label class="search-field">${icon('search')}<input id="${id}" type="search" placeholder="${placeholder}" autocomplete="off" /></label>`;
}

export function pagination(): string {
  return '<div class="pagination"><button type="button" data-toast="已经是第一页">‹</button><button class="is-active" type="button">1</button><button type="button" data-toast="原型当前只有一页数据">2</button><button type="button" data-toast="原型当前只有一页数据">›</button></div>';
}

let toastTimer: ReturnType<typeof setTimeout>;

export function showToast(message: string): void {
  const toast = $('#toast');
  toast.textContent = message;
  toast.classList.add('is-visible');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove('is-visible'), 2200);
}

export interface ModalOptions {
  eyebrow?: string;
  title: string;
  body: string;
  confirm?: string;
  danger?: boolean;
  /** 返回 false 表示本次操作未完成，弹窗保持打开，已填内容不丢。 */
  onConfirm?: () => unknown | Promise<unknown>;
}

export function openModal({ eyebrow = '管理操作', title, body, confirm = '保存', danger = false, onConfirm }: ModalOptions): void {
  const modal = $<HTMLDialogElement>('#modal');
  $('#modal-eyebrow').textContent = eyebrow;
  $('#modal-title').textContent = title;
  $('#modal-body').innerHTML = body;
  $('#modal-footer').innerHTML = `<button class="button button--neutral" id="modal-cancel" type="button">取消</button><button class="button ${danger ? 'button--danger' : 'button--primary'}" id="modal-confirm" type="button">${confirm}</button>`;
  // 关闭与取消都必须是 type="button" 并手动 close：留在 form method="dialog" 里的 submit 按钮会被
  // 输入框里的回车触发隐式提交，弹窗直接关闭且绕过 onConfirm，管理员刚填的内容全部丢失。
  // 关闭按钮是静态节点，用 onclick 赋值而不是 addEventListener，避免每次打开都叠加一个监听。
  const dismiss = (): void => modal.close();
  $<HTMLButtonElement>('#modal .modal__close').onclick = dismiss;
  $<HTMLButtonElement>('#modal-cancel').onclick = dismiss;
  // onConfirm 可能是网络请求：期间禁用按钮防重复提交，返回 false 时保留已填内容。
  $('#modal-confirm').addEventListener('click', async (event) => {
    const button = event.currentTarget as HTMLButtonElement;
    button.disabled = true;
    try {
      if (await onConfirm?.() === false) return;
      modal.close();
    } finally {
      button.disabled = false;
    }
  });
  modal.showModal();
}

export function openDrawer({ eyebrow = '详情', title, body }: { eyebrow?: string; title: string; body: string }): void {
  $('#drawer-eyebrow').textContent = eyebrow;
  $('#drawer-title').textContent = title;
  $('#drawer-body').innerHTML = body;
  $<HTMLDialogElement>('#drawer').showModal();
}

/** 读取弹窗表单字段；表单结构由同一模块渲染，缺字段属于编码错误而非运行时输入问题。 */
export function fieldValue(form: ParentNode, name: string): string {
  return $<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>(`[name=${name}]`, form).value.trim();
}
