import { afterEach, describe, expect, it, vi } from 'vitest';

import { safe, showToast, statusBadge, sourceStatusBadge, timeText } from './ui';

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('safe', () => {
  it('转义所有会破坏模板的字符', () => {
    expect(safe(`<img src=x onerror="a('b')">&`)).toBe(
      '&lt;img src=x onerror=&quot;a(&#39;b&#39;)&quot;&gt;&amp;',
    );
  });

  it('空值渲染成空串而不是 null 字面量', () => {
    expect(safe(null)).toBe('');
    expect(safe(undefined)).toBe('');
  });
});

describe('timeText', () => {
  it('缺失或非法时间统一显示占位符', () => {
    expect(timeText(null)).toBe('—');
    expect(timeText('not-a-date')).toBe('—');
  });

  it('一分钟内显示刚刚', () => {
    expect(timeText(new Date(Date.now() - 30_000).toISOString())).toBe('刚刚');
  });

  it('一小时内按分钟、一天内按小时显示相对时间', () => {
    expect(timeText(new Date(Date.now() - 5 * 60_000).toISOString())).toBe('5 分钟前');
    expect(timeText(new Date(Date.now() - 3 * 3_600_000).toISOString())).toBe('3 小时前');
  });

  it('超过一天回退到绝对时间', () => {
    const text = timeText(new Date(Date.now() - 3 * 86_400_000).toISOString());
    expect(text).not.toBe('—');
    expect(text).not.toContain('前');
  });
});

describe('statusBadge', () => {
  it('把投递账本状态映射成中文标签', () => {
    expect(statusBadge('sent')).toContain('成功');
    expect(statusBadge('retryable')).toContain('重试中');
  });

  it('未知状态原样展示，不吞掉后端新增的状态值', () => {
    const badge = statusBadge('brand_new_status');
    expect(badge).toContain('brand_new_status');
    expect(badge).toContain('badge--neutral');
  });

  it('来源抓取失败在来源页读作异常', () => {
    expect(sourceStatusBadge('failed')).toContain('异常');
    expect(sourceStatusBadge('healthy')).toContain('正常');
  });
});

describe('showToast', () => {
  it('弹窗打开时把提示放进顶层弹窗，关闭后放回页面', () => {
    vi.stubGlobal('setTimeout', vi.fn(() => 1));
    vi.stubGlobal('clearTimeout', vi.fn());
    const toast = { textContent: '', classList: { add: vi.fn(), remove: vi.fn() } };
    const body = { append: vi.fn() };
    let onClose: (() => void) | undefined;
    let containsToast = false;
    const dialog = {
      open: true,
      contains: vi.fn(() => containsToast),
      append: vi.fn(() => { containsToast = true; }),
      addEventListener: vi.fn((_type: string, listener: () => void) => { onClose = listener; }),
    };
    vi.stubGlobal('document', {
      body,
      querySelector: vi.fn((selector: string) => selector === '#toast' ? toast : dialog),
    });

    showToast('抓取失败');

    expect(dialog.append).toHaveBeenCalledWith(toast);
    expect(dialog.addEventListener).toHaveBeenCalledWith('close', expect.any(Function), { once: true });
    dialog.open = false;
    onClose?.();
    expect(body.append).toHaveBeenCalledWith(toast);
  });

  it('旧弹窗关闭时不把已进入新弹窗的提示搬回页面', () => {
    vi.stubGlobal('setTimeout', vi.fn(() => 1));
    vi.stubGlobal('clearTimeout', vi.fn());
    const toast = { textContent: '', classList: { add: vi.fn(), remove: vi.fn() } };
    const body = { append: vi.fn() };
    let onClose: (() => void) | undefined;
    let containsToast = false;
    const dialog = {
      open: true,
      contains: vi.fn(() => containsToast),
      append: vi.fn(() => { containsToast = true; }),
      addEventListener: vi.fn((_type: string, listener: () => void) => { onClose = listener; }),
    };
    vi.stubGlobal('document', {
      body,
      querySelector: vi.fn((selector: string) => selector === '#toast' ? toast : dialog),
    });

    showToast('抓取失败');
    containsToast = false;
    dialog.open = false;
    onClose?.();

    expect(body.append).not.toHaveBeenCalled();
  });
});
