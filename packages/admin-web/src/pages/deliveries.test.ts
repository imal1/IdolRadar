import { describe, expect, it } from 'vitest';

import { deliveryKey } from './deliveries';

describe('deliveryKey', () => {
  it('用 (postId, userId) 复合键定位投递，同一动态的不同用户不会互相命中', () => {
    const first = deliveryKey({ postId: 'post-1', userId: 'user-a' });
    const second = deliveryKey({ postId: 'post-1', userId: 'user-b' });
    expect(first).not.toBe(second);
    expect(deliveryKey({ postId: 'post-1', userId: 'user-a' })).toBe(first);
  });
});
