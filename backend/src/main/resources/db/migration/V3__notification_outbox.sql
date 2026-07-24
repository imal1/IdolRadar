-- V3 增加事务 outbox，保证动态一旦提交，其通知调度意图不会丢失。
-- 必须先于读写 outbox 的 Worker 部署；此表是持久化协调机制，不是临时队列。
-- 每个偶像仅保留一行，多个新动态在 fanout 前主动合并为该偶像的最新 post。
-- processing 必须持有 lease，其他状态必须清空 lease；末尾 CHECK 强制保证双向一致。
CREATE TABLE notification_outbox (
  idol_id varchar(128) PRIMARY KEY
    REFERENCES idols (id) ON UPDATE CASCADE ON DELETE CASCADE,
  post_id varchar(128) NOT NULL
    REFERENCES posts (id) ON UPDATE CASCADE ON DELETE CASCADE,
  status varchar(16) NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'processing', 'retryable', 'completed')),
  attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 100),
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  lease_expires_at timestamptz,
  error_code varchar(64),
  completed_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK ((status = 'processing') = (lease_expires_at IS NOT NULL))
);

-- 到期部分索引按 next_attempt_at 领取 pending/retryable，排除已完成任务。
CREATE INDEX notification_outbox_due_idx
  ON notification_outbox (next_attempt_at, idol_id)
  WHERE status IN ('pending', 'retryable');

-- lease 部分索引用于快速发现、恢复租约过期的 processing 任务。
CREATE INDEX notification_outbox_lease_idx
  ON notification_outbox (lease_expires_at, idol_id)
  WHERE status = 'processing';
