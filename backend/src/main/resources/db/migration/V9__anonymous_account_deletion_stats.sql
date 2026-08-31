-- V9：增加匿名注销回执解决响应丢失时的结果确认，并按上海自然日保留运营汇总。
-- 部署顺序：必须先于包含新注销契约的 app 版本执行；旧版 app 不读写新表，可安全共存。
-- 隐私边界：回执仅存随机 request_id/completed，汇总仅存日期/数量；严禁用户标识和精确时间。
-- 约束与索引：两个主键分别承担请求占用/回执查询和日期统计，无需额外索引。

CREATE TABLE idr_account_deletion_receipt (
  request_id uuid NOT NULL,
  completed boolean NOT NULL DEFAULT FALSE,
  CONSTRAINT pk_idr_account_deletion_receipt PRIMARY KEY (request_id)
);

COMMENT ON TABLE idr_account_deletion_receipt IS
  '匿名账号注销回执；仅证明随机请求是否完成，不得保存任何用户身份或时间';
COMMENT ON COLUMN idr_account_deletion_receipt.request_id IS
  '服务端生成的随机注销请求标识，用于并发占用和结果查询';
COMMENT ON COLUMN idr_account_deletion_receipt.completed IS
  '物理删除与匿名日汇总是否已在同一事务完成';

CREATE TABLE idr_account_deletion_daily (
  deletion_date date NOT NULL,
  deletion_count bigint NOT NULL,
  CONSTRAINT pk_idr_account_deletion_daily PRIMARY KEY (deletion_date),
  CONSTRAINT ck_idr_account_deletion_daily_count CHECK (deletion_count > 0)
);

COMMENT ON TABLE idr_account_deletion_daily IS
  '按上海自然日汇总的匿名账号注销数量；不得保存任何用户标识或精确时间';
COMMENT ON COLUMN idr_account_deletion_daily.deletion_date IS
  '账号注销发生的上海自然日';
COMMENT ON COLUMN idr_account_deletion_daily.deletion_count IS
  '该自然日成功物理删除的账号总数';
