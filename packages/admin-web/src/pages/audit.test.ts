import { afterEach, beforeEach, describe, expect, it } from 'vitest';

import { state } from '../state';
import { actions, applySearch, queryParams, render } from './audit';

const original = structuredClone(state);

beforeEach(() => {
  Object.assign(state, structuredClone(original), {
    auditResult: 'failed',
    auditSearch: 'ops-admin',
    auditRangeHours: 168,
    auditCursors: [null],
    auditNextCursor: 'next-page',
    audits: [{
      id: 'f720d0d8-383e-4d72-beb2-d34651369d78',
      operator: '<script>ops-admin</script>',
      action: 'HTTP_PATCH',
      resourceType: 'admin_route',
      resourceId: '/admin/v1/idols/idol-1',
      requestId: 'request-67',
      httpStatus: 409,
      succeeded: false,
      createdAt: '2026-09-01T01:02:03Z',
    }],
  });
});

afterEach(() => Object.assign(state, structuredClone(original)));

describe('真实审计日志页', () => {
  it('渲染服务端记录并删除演示控件与敏感详情', () => {
    const html = render();

    expect(html).toContain('&lt;script&gt;ops-admin&lt;/script&gt;');
    expect(html).toContain('HTTP_PATCH');
    expect(html).toContain('/admin/v1/idols/idol-1');
    expect(html).toContain('request-67');
    expect(html).toContain('409');
    expect(html).toContain('失败');
    expect(html).not.toContain('<script>ops-admin</script>');
    expect(html).not.toContain('data-toast');
    expect(html).not.toContain('导出当前结果');
    expect(html).not.toContain('修改前');
    expect(html).not.toContain('修改后');
    expect(html).not.toContain('详情</button>');
  });

  it('把结果、时间、搜索和游标交给服务端', () => {
    Object.assign(state, { auditCursors: [null, 'current-page'] });
    const query = queryParams();

    expect(query.get('result')).toBe('failed');
    expect(query.get('rangeHours')).toBe('168');
    expect(query.get('search')).toBe('ops-admin');
    expect(query.get('cursor')).toBe('current-page');
  });

  it('用游标栈执行真实前后翻页', () => {
    actions['audit-next']?.('');
    expect(state.auditCursors).toEqual([null, 'next-page']);

    actions['audit-previous']?.('');
    expect(state.auditCursors).toEqual([null]);
  });

  it('确认搜索时更新条件并回到第一页', () => {
    Object.assign(state, { auditSearch: 'old', auditCursors: [null, 'current-page'], auditNextCursor: 'next' });

    applySearch('  request-67  ');

    expect(state.auditSearch).toBe('request-67');
    expect(state.auditCursors).toEqual([null]);
    expect(state.auditNextCursor).toBeNull();
  });

  it('没有记录时展示空态并禁用翻页', () => {
    Object.assign(state, { audits: [], auditNextCursor: null });
    const html = render();

    expect(html).toContain('暂无审计记录');
    expect(html).toContain('data-action="audit-previous" disabled');
    expect(html).toContain('data-action="audit-next" disabled');
  });
});
