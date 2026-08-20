import { describe, expect, it } from 'vitest';

import { seriesPath } from './dashboard';
import type { MetricsTrendPoint } from '../types';

function point(date: string, guarded: number): MetricsTrendPoint {
  return { date, newUsers: 0, guarded, subscribed: 0, opened: 0 };
}

describe('seriesPath', () => {
  it('把序列铺满绘图区，最大值贴顶、零值贴底', () => {
    const path = seriesPath([point('2026-08-01', 0), point('2026-08-02', 10)], 'guarded', 10);
    // 左端 x=48 且 y 在底部 237；右端 x=700 且 y 在顶部 25。
    expect(path).toBe('M48.0 237.0L700.0 25.0');
  });

  it('单个数据点不会因为除零产生 NaN 路径', () => {
    const path = seriesPath([point('2026-08-01', 3)], 'guarded', 10);
    expect(path).not.toContain('NaN');
    expect(path.startsWith('M48.0 ')).toBe(true);
  });

  it('上界大于实际值时线不会画出绘图区', () => {
    const path = seriesPath([point('2026-08-01', 5)], 'guarded', 10);
    const y = Number(path.replace('M48.0 ', ''));
    expect(y).toBeGreaterThan(25);
    expect(y).toBeLessThan(237);
  });
});
