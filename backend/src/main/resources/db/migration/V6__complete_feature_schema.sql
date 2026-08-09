-- V6 补齐已确认功能落地前仍缺少的数据库约束、关联字段、用户资料字段和统计索引。
-- 部署顺序：在 V5 后执行；本版本只增加兼容字段、约束和索引，现有 API/Worker 可继续运行。
-- 锁说明：唯一约束和索引创建会扫描现有数据并短暂锁表；当前未正式上线，可直接在低流量窗口执行。
-- 数据前提：同一 idol 下不得已有重复 rss_url；发现重复时迁移应失败，由管理员先确认保留哪条源。
-- 回滚说明：新字段开始写入后不可直接删除；回滚前必须先导出用户资料和申请审核关联。

-- -----------------------------------------------------------------------------
-- 1. 管理端新增源时阻止同一 idol 重复配置同一抓取地址
-- -----------------------------------------------------------------------------

ALTER TABLE idr_source
  ADD CONSTRAINT uk_idr_source_idol_id_rss_url UNIQUE (idol_id, rss_url);

COMMENT ON CONSTRAINT uk_idr_source_idol_id_rss_url ON idr_source IS
  '阻止同一偶像重复配置完全相同的抓取地址；URL 规范化仍由管理端在写入前完成';

-- -----------------------------------------------------------------------------
-- 2. idol 申请审核通过后关联实际创建或匹配到的正式 idol
-- -----------------------------------------------------------------------------

ALTER TABLE idr_idol_request
  DROP CONSTRAINT ck_idr_idol_request_review,
  ADD COLUMN approved_idol_id varchar(128),
  ADD CONSTRAINT fk_idr_idol_request_approved_idr_idol
    FOREIGN KEY (approved_idol_id) REFERENCES idr_idol (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  ADD CONSTRAINT ck_idr_idol_request_review CHECK (
    (status = 'pending'
      AND reviewed_by IS NULL AND reviewed_at IS NULL AND approved_idol_id IS NULL)
    OR
    (status = 'approved'
      AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND approved_idol_id IS NOT NULL)
    OR
    (status = 'rejected'
      AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND approved_idol_id IS NULL)
  );

COMMENT ON COLUMN idr_idol_request.approved_idol_id IS
  '申请审核通过后实际创建或匹配到的正式偶像 ID；待审核或驳回时为空';

-- -----------------------------------------------------------------------------
-- 3. 用户明确授权后的微信展示资料；未授权时保持 NULL 并继续展示默认资料
-- -----------------------------------------------------------------------------

ALTER TABLE idr_user
  ADD COLUMN nickname varchar(128),
  ADD COLUMN avatar_url text,
  ADD COLUMN profile_authorized_at timestamptz,
  ADD CONSTRAINT ck_idr_user_nickname
    CHECK (nickname IS NULL OR length(btrim(nickname)) > 0),
  ADD CONSTRAINT ck_idr_user_avatar_url
    CHECK (avatar_url IS NULL OR length(btrim(avatar_url)) > 0);

COMMENT ON COLUMN idr_user.nickname IS
  '用户明确授权后保存的微信展示昵称；未授权或撤回资料时为空';
COMMENT ON COLUMN idr_user.avatar_url IS
  '用户明确授权后保存的微信头像地址；未授权或撤回资料时为空';
COMMENT ON COLUMN idr_user.profile_authorized_at IS
  '用户最近一次明确授权昵称和头像资料的时间；静默登录本身不得写入';

-- -----------------------------------------------------------------------------
-- 4. 管理看板按时间区间统计时避免全表扫描
-- -----------------------------------------------------------------------------

CREATE INDEX idx_idr_user_created_at ON idr_user (created_at);
CREATE INDEX idx_idr_notification_delivery_created_at
  ON idr_notification_delivery (created_at);

COMMENT ON INDEX idx_idr_user_created_at IS
  '支持核心指标按新用户创建时间筛选统计区间';
COMMENT ON INDEX idx_idr_notification_delivery_created_at IS
  '支持推送看板不限投递状态地按创建时间筛选统计区间';
