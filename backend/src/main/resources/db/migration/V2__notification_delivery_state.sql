-- V2 将投递账本升级为可抵抗进程崩溃的预留、发送、重试状态机。
-- 必须先于新版 Worker 部署；约束替换、数据回填、索引创建必须按此顺序执行。
-- 尝试时间、重试计划、模板身份、额度预留共同保存跨重启的投递意图。
ALTER TABLE notification_deliveries
  DROP CONSTRAINT notification_deliveries_status_check;

ALTER TABLE notification_deliveries
  ADD COLUMN attempted_at timestamptz,
  ADD COLUMN finished_at timestamptz,
  ADD COLUMN template_id varchar(128),
  ADD COLUMN attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 100),
  ADD COLUMN next_attempt_at timestamptz,
  ADD COLUMN quota_reserved boolean NOT NULL DEFAULT false,
  ALTER COLUMN status SET DEFAULT 'reserved';

-- 额度只对用户授权时的模板有效；模板变更后不能消费旧授权。
ALTER TABLE users
  ADD COLUMN subscribe_template_id varchar(128);

-- 旧 pending 记录无法证明微信是否已接收请求。
-- 转为 uncertain，禁止自动重试造成用户收到重复通知。
UPDATE notification_deliveries
SET status = 'uncertain',
    error_code = COALESCE(error_code, 'LEGACY_OUTCOME_UNKNOWN'),
    finished_at = COALESCE(finished_at, updated_at)
WHERE status = 'pending';

-- 仅允许新版 Worker 能正确处理的持久化状态。
ALTER TABLE notification_deliveries
  ADD CONSTRAINT notification_deliveries_status_check
  CHECK (status IN ('reserved', 'sending', 'retryable', 'sent', 'failed', 'uncertain'));

-- Worker 崩溃后快速定位遗留的预留、发送记录，用于对账。
CREATE INDEX notification_deliveries_reconciliation_idx
  ON notification_deliveries (status, updated_at)
  WHERE status IN ('reserved', 'sending');

-- 按确定顺序选择到期重试，避免扫描已完成投递。
CREATE INDEX notification_deliveries_retry_idx
  ON notification_deliveries (next_attempt_at, post_id, user_id)
  WHERE status = 'retryable';
