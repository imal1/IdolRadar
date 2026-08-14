import { showToast } from './ui';

export const ADMIN_TOKEN_KEY = 'idolradar.admin.token';

/**
 * api 层与主流程之间的回调：这一层不认识页面渲染，也不能反向 import main.ts（会形成循环依赖）。
 * 由 main.ts 在启动时注入；未注入前调用是无操作，避免初始化顺序出错时抛异常。
 */
interface ApiHandlers {
  /** 重新拉取当前页数据并重绘；必须返回刷新完成的 Promise，写操作要等它结束才算成功。 */
  reload: () => Promise<void>;
  /** 会话失效，切回登录页。 */
  unauthorized: (message: string) => void;
}

let handlers: ApiHandlers = { reload: async () => {}, unauthorized: () => {} };

export function bindApiHandlers(next: ApiHandlers): void {
  handlers = next;
}

interface Envelope<T> {
  ok: boolean;
  data: T;
  error?: { code?: string; message?: string };
}

export function errorMessage(error: unknown, fallback: string): string {
  return error instanceof Error ? error.message : fallback;
}

/**
 * 调用管理端 API。
 *
 * <p>token 存在 sessionStorage：生命周期跟随当前标签页，关闭即失效，不落长期 localStorage。
 */
export async function adminRequest<T>(path: string, options: RequestInit = {}): Promise<T> {
  const token = sessionStorage.getItem(ADMIN_TOKEN_KEY);
  const response = await fetch(path, {
    ...options,
    headers: {
      Accept: 'application/json',
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...options.headers,
    },
  });
  const body = (await response.json().catch(() => null)) as Envelope<T> | null;
  if (response.status === 401) {
    // 401 统一清理管理员 token；禁止继续展示缓存的管理数据。
    sessionStorage.removeItem(ADMIN_TOKEN_KEY);
    handlers.unauthorized('登录已失效，请重新登录。');
  }
  if (!response.ok || !body?.ok) {
    throw new Error(body?.error?.message || '管理后台暂时不可用');
  }
  return body.data;
}

/**
 * 提交一次管理端写操作。
 *
 * <p>失败时返回 false 让弹窗保持打开：表单内容还在，管理员改完可以直接重试，
 * 不需要把刚填的 RSS 地址再敲一遍。成功也要等 reload 结束才返回：调用方据此关闭弹窗，
 * 期间确认按钮保持禁用，堵住重复提交的窗口，同时保证提示出现时表格已是新数据。
 */
export async function submitChange(action: () => Promise<unknown>, success: string): Promise<boolean> {
  try {
    await action();
  } catch (error) {
    showToast(errorMessage(error, '操作失败'));
    return false;
  }
  await handlers.reload();
  showToast(success);
  return true;
}

/** 筛选条件变化后的重载：不阻塞事件处理，失败由 reload 自己提示。 */
export function requestReload(): void {
  void handlers.reload();
}
