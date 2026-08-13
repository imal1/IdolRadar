import { describe, expect, it } from 'vitest';

import { safe, statusBadge, sourceStatusBadge, timeText } from './ui';

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
