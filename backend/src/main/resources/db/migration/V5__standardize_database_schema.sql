-- V5 统一 IdolRadar 业务表命名，并一次补齐已确认需求使用的基础数据结构。
-- 部署顺序：必须与使用 idr_ 新表名的 API、Worker、Seed 同一版本发布；旧进程不可继续访问旧表名。
-- 迁移策略：只重命名现有对象、增加可兼容字段和新表，不删除既有业务字段或数据。
-- 锁说明：ALTER TABLE RENAME/ADD COLUMN 会短暂取得表级锁；当前尚未正式上线，应在停止 API、Worker 后执行。
-- 回滚说明：表重命名可人工逆向执行；新字段、新表开始写入数据后不可直接删除，回滚前必须先导出新增数据。
-- Flyway 的 flyway_schema_history 是基础设施表，不属于业务表，本版本不重命名。

-- -----------------------------------------------------------------------------
-- 1. 现有业务表统一为“idr_ + 单数实体名”
-- -----------------------------------------------------------------------------

ALTER TABLE idols RENAME TO idr_idol;
ALTER TABLE sources RENAME TO idr_source;
ALTER TABLE posts RENAME TO idr_post;
ALTER TABLE users RENAME TO idr_user;
ALTER TABLE sessions RENAME TO idr_user_session;
ALTER TABLE notification_deliveries RENAME TO idr_notification_delivery;
ALTER TABLE notification_outbox RENAME TO idr_notification_outbox;

-- 主键、唯一约束、外键和检查约束必须同步改名，否则数据库对象仍保留旧表名前缀。
ALTER TABLE idr_idol RENAME CONSTRAINT idols_pkey TO pk_idr_idol;
ALTER TABLE idr_idol RENAME CONSTRAINT idols_name_check TO ck_idr_idol_name;

ALTER TABLE idr_source RENAME CONSTRAINT sources_pkey TO pk_idr_source;
ALTER TABLE idr_source RENAME CONSTRAINT sources_idol_id_fkey TO fk_idr_source_idr_idol;
ALTER TABLE idr_source RENAME CONSTRAINT sources_rss_url_check TO ck_idr_source_rss_url;
ALTER TABLE idr_source RENAME CONSTRAINT sources_last_fetch_status_check TO ck_idr_source_last_fetch_status;
ALTER TABLE idr_source RENAME CONSTRAINT sources_last_fetch_item_count_check TO ck_idr_source_last_fetch_item_count;
ALTER TABLE idr_source RENAME CONSTRAINT sources_last_fetch_new_count_check TO ck_idr_source_last_fetch_new_count;

ALTER TABLE idr_post RENAME CONSTRAINT posts_pkey TO pk_idr_post;
ALTER TABLE idr_post RENAME CONSTRAINT posts_idol_id_fkey TO fk_idr_post_idr_idol;
ALTER TABLE idr_post RENAME CONSTRAINT posts_source_id_fkey TO fk_idr_post_idr_source;
ALTER TABLE idr_post RENAME CONSTRAINT posts_title_check TO ck_idr_post_title;
ALTER TABLE idr_post RENAME CONSTRAINT posts_link_key TO uk_idr_post_link;
ALTER TABLE idr_post RENAME CONSTRAINT posts_link_check TO ck_idr_post_link;

ALTER TABLE idr_user RENAME CONSTRAINT users_pkey TO pk_idr_user;
ALTER TABLE idr_user RENAME CONSTRAINT users_openid_key TO uk_idr_user_openid;
ALTER TABLE idr_user RENAME CONSTRAINT users_idol_id_fkey TO fk_idr_user_idr_idol;
ALTER TABLE idr_user RENAME CONSTRAINT users_subscribe_quota_check TO ck_idr_user_subscribe_quota;

ALTER TABLE idr_user_session RENAME CONSTRAINT sessions_pkey TO pk_idr_user_session;
ALTER TABLE idr_user_session RENAME CONSTRAINT sessions_token_hash_check TO ck_idr_user_session_token_hash;
ALTER TABLE idr_user_session RENAME CONSTRAINT sessions_user_id_fkey TO fk_idr_user_session_idr_user;
ALTER TABLE idr_user_session RENAME CONSTRAINT sessions_check TO ck_idr_user_session_expires_at;

ALTER TABLE idr_notification_delivery
  RENAME CONSTRAINT notification_deliveries_pkey TO pk_idr_notification_delivery;
ALTER TABLE idr_notification_delivery
  RENAME CONSTRAINT notification_deliveries_post_id_fkey TO fk_idr_notification_delivery_idr_post;
ALTER TABLE idr_notification_delivery
  RENAME CONSTRAINT notification_deliveries_user_id_fkey TO fk_idr_notification_delivery_idr_user;
ALTER TABLE idr_notification_delivery
  RENAME CONSTRAINT notification_deliveries_status_check TO ck_idr_notification_delivery_status;
ALTER TABLE idr_notification_delivery
  RENAME CONSTRAINT notification_deliveries_attempt_count_check TO ck_idr_notification_delivery_attempt_count;

ALTER TABLE idr_notification_outbox
  RENAME CONSTRAINT notification_outbox_pkey TO pk_idr_notification_outbox;
ALTER TABLE idr_notification_outbox
  RENAME CONSTRAINT notification_outbox_idol_id_fkey TO fk_idr_notification_outbox_idr_idol;
ALTER TABLE idr_notification_outbox
  RENAME CONSTRAINT notification_outbox_post_id_fkey TO fk_idr_notification_outbox_idr_post;
ALTER TABLE idr_notification_outbox
  RENAME CONSTRAINT notification_outbox_status_check TO ck_idr_notification_outbox_status;
ALTER TABLE idr_notification_outbox
  RENAME CONSTRAINT notification_outbox_attempt_count_check TO ck_idr_notification_outbox_attempt_count;
ALTER TABLE idr_notification_outbox
  RENAME CONSTRAINT notification_outbox_check TO ck_idr_notification_outbox_processing_lease;

-- 显式索引同步使用 idx_/uk_ + 完整表名，便于在执行计划中直接识别用途。
ALTER INDEX idols_enabled_name_idx RENAME TO idx_idr_idol_enabled_name;
ALTER INDEX idols_enabled_id_idx RENAME TO idx_idr_idol_enabled_id;
ALTER INDEX sources_enabled_idol_id_idx RENAME TO idx_idr_source_enabled_idol_id;
ALTER INDEX sources_enabled_id_idx RENAME TO idx_idr_source_enabled_id;
ALTER INDEX sources_idol_id_id_idx RENAME TO idx_idr_source_idol_id_id;
ALTER INDEX sources_idol_id_enabled_id_idx RENAME TO idx_idr_source_idol_id_enabled_id;
ALTER INDEX posts_idol_id_published_at_id_idx RENAME TO idx_idr_post_idol_id_published_at_id;
ALTER INDEX posts_source_id_published_at_idx RENAME TO idx_idr_post_source_id_published_at;
ALTER INDEX users_idol_id_subscribe_quota_id_idx RENAME TO idx_idr_user_idol_id_subscribe_quota_id;
ALTER INDEX users_notification_targets_idx RENAME TO idx_idr_user_notification_targets;
ALTER INDEX sessions_user_id_idx RENAME TO idx_idr_user_session_user_id;
ALTER INDEX sessions_expires_at_idx RENAME TO idx_idr_user_session_expires_at;
ALTER INDEX notification_deliveries_user_id_created_at_idx
  RENAME TO idx_idr_notification_delivery_user_id_created_at;
ALTER INDEX notification_deliveries_status_created_at_idx
  RENAME TO idx_idr_notification_delivery_status_created_at;
ALTER INDEX notification_deliveries_reconciliation_idx
  RENAME TO idx_idr_notification_delivery_reconciliation;
ALTER INDEX notification_deliveries_retry_idx
  RENAME TO idx_idr_notification_delivery_retry;
ALTER INDEX notification_outbox_due_idx RENAME TO idx_idr_notification_outbox_due;
ALTER INDEX notification_outbox_lease_idx RENAME TO idx_idr_notification_outbox_lease;

-- -----------------------------------------------------------------------------
-- 2. 扩展现有表：管理版本、源健康、跨源去重、转化里程碑、推送回访
-- -----------------------------------------------------------------------------

ALTER TABLE idr_idol
  ADD COLUMN version integer NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_idr_idol_version CHECK (version >= 0);

ALTER TABLE idr_source
  ADD COLUMN display_name varchar(128),
  ADD COLUMN last_success_at timestamptz,
  ADD COLUMN consecutive_failures integer NOT NULL DEFAULT 0,
  ADD COLUMN version integer NOT NULL DEFAULT 0;

-- 已有源没有展示名时，使用“idol 名称 · 渠道”生成可读默认值；后续由 seed 或管理端维护。
UPDATE idr_source source
SET display_name = idol.name || ' · ' || source.channel,
    last_success_at = CASE
      WHEN source.last_fetch_status = 'success' THEN source.last_fetch_at
      ELSE NULL
    END,
    consecutive_failures = CASE
      WHEN source.last_fetch_status = 'failed' THEN 1
      ELSE 0
    END
FROM idr_idol idol
WHERE idol.id = source.idol_id;

ALTER TABLE idr_source
  ALTER COLUMN display_name SET NOT NULL,
  ADD CONSTRAINT ck_idr_source_display_name CHECK (length(btrim(display_name)) > 0),
  ADD CONSTRAINT ck_idr_source_consecutive_failures CHECK (consecutive_failures >= 0),
  ADD CONSTRAINT ck_idr_source_version CHECK (version >= 0);

CREATE INDEX idx_idr_source_fetch_health
  ON idr_source (enabled, last_fetch_status, last_success_at);

ALTER TABLE idr_post
  ADD COLUMN dedup_key varchar(256),
  ADD CONSTRAINT ck_idr_post_dedup_key
    CHECK (dedup_key IS NULL OR length(btrim(dedup_key)) > 0);

-- 同一 idol 下才做跨源幂等；无法生成可靠标识的内容保留 NULL，避免标题相似导致误合并。
CREATE UNIQUE INDEX uk_idr_post_idol_id_dedup_key
  ON idr_post (idol_id, dedup_key)
  WHERE dedup_key IS NOT NULL;

ALTER TABLE idr_user
  ADD COLUMN first_guarded_at timestamptz,
  ADD COLUMN first_subscribed_at timestamptz;

UPDATE idr_user
SET first_guarded_at = guarding_since,
    first_subscribed_at = subscribed_at;

ALTER TABLE idr_notification_delivery
  ADD COLUMN first_opened_at timestamptz,
  ADD COLUMN last_opened_at timestamptz,
  ADD COLUMN open_count integer NOT NULL DEFAULT 0,
  ADD CONSTRAINT ck_idr_notification_delivery_open_count CHECK (open_count >= 0),
  ADD CONSTRAINT ck_idr_notification_delivery_open_times CHECK (
    (first_opened_at IS NULL AND last_opened_at IS NULL AND open_count = 0)
    OR
    (first_opened_at IS NOT NULL AND last_opened_at IS NOT NULL
      AND open_count > 0 AND last_opened_at >= first_opened_at)
  );

CREATE INDEX idx_idr_notification_delivery_first_opened_at
  ON idr_notification_delivery (first_opened_at)
  WHERE first_opened_at IS NOT NULL;

-- -----------------------------------------------------------------------------
-- 3. 管理员身份、会话与审计
-- -----------------------------------------------------------------------------

CREATE TABLE idr_admin_account (
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  username varchar(64) NOT NULL,
  password_hash varchar(255) NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  version integer NOT NULL DEFAULT 0,
  last_login_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_idr_admin_account PRIMARY KEY (id),
  CONSTRAINT uk_idr_admin_account_username UNIQUE (username),
  CONSTRAINT ck_idr_admin_account_username CHECK (length(btrim(username)) BETWEEN 3 AND 64),
  CONSTRAINT ck_idr_admin_account_password_hash CHECK (length(password_hash) >= 20),
  CONSTRAINT ck_idr_admin_account_version CHECK (version >= 0)
);

CREATE TABLE idr_admin_session (
  token_hash char(64) NOT NULL,
  admin_id uuid NOT NULL,
  expires_at timestamptz NOT NULL,
  last_used_at timestamptz NOT NULL DEFAULT now(),
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_idr_admin_session PRIMARY KEY (token_hash),
  CONSTRAINT fk_idr_admin_session_idr_admin_account
    FOREIGN KEY (admin_id) REFERENCES idr_admin_account (id) ON DELETE CASCADE,
  CONSTRAINT ck_idr_admin_session_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_idr_admin_session_expires_at CHECK (expires_at > created_at),
  CONSTRAINT ck_idr_admin_session_revoked_at CHECK (revoked_at IS NULL OR revoked_at >= created_at)
);

CREATE INDEX idx_idr_admin_session_admin_id ON idr_admin_session (admin_id);
CREATE INDEX idx_idr_admin_session_expires_at ON idr_admin_session (expires_at);

CREATE TABLE idr_admin_audit_log (
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  admin_id uuid NOT NULL,
  action varchar(64) NOT NULL,
  resource_type varchar(64) NOT NULL,
  resource_id varchar(128),
  request_id varchar(64),
  detail jsonb NOT NULL DEFAULT '{}'::jsonb,
  succeeded boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_idr_admin_audit_log PRIMARY KEY (id),
  CONSTRAINT fk_idr_admin_audit_log_idr_admin_account
    FOREIGN KEY (admin_id) REFERENCES idr_admin_account (id) ON DELETE RESTRICT,
  CONSTRAINT ck_idr_admin_audit_log_action CHECK (length(btrim(action)) > 0),
  CONSTRAINT ck_idr_admin_audit_log_resource_type CHECK (length(btrim(resource_type)) > 0)
);

CREATE INDEX idx_idr_admin_audit_log_admin_id_created_at
  ON idr_admin_audit_log (admin_id, created_at DESC);
CREATE INDEX idx_idr_admin_audit_log_resource_created_at
  ON idr_admin_audit_log (resource_type, resource_id, created_at DESC);

-- -----------------------------------------------------------------------------
-- 4. 多 idol 守护关联：先与 idr_user.idol_id 并存，后续 API/Worker 分阶段迁移
-- -----------------------------------------------------------------------------

CREATE TABLE idr_user_guard (
  user_id uuid NOT NULL,
  idol_id varchar(128) NOT NULL,
  guarding_since timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_idr_user_guard PRIMARY KEY (user_id, idol_id),
  CONSTRAINT fk_idr_user_guard_idr_user
    FOREIGN KEY (user_id) REFERENCES idr_user (id) ON DELETE CASCADE,
  CONSTRAINT fk_idr_user_guard_idr_idol
    FOREIGN KEY (idol_id) REFERENCES idr_idol (id) ON UPDATE CASCADE ON DELETE RESTRICT
);

CREATE INDEX idx_idr_user_guard_idol_id_user_id
  ON idr_user_guard (idol_id, user_id);

-- 回填现有单 idol 关系；空 guarding_since 使用用户创建时间，避免产生无意义的当前时间。
INSERT INTO idr_user_guard (user_id, idol_id, guarding_since, created_at, updated_at)
SELECT id, idol_id, COALESCE(guarding_since, created_at), created_at, updated_at
FROM idr_user
WHERE idol_id IS NOT NULL
ON CONFLICT (user_id, idol_id) DO NOTHING;

-- -----------------------------------------------------------------------------
-- 5. 用户来源屏蔽：只保存关闭项，无记录即默认接收该源
-- -----------------------------------------------------------------------------

CREATE TABLE idr_user_source_mute (
  user_id uuid NOT NULL,
  source_id varchar(128) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_idr_user_source_mute PRIMARY KEY (user_id, source_id),
  CONSTRAINT fk_idr_user_source_mute_idr_user
    FOREIGN KEY (user_id) REFERENCES idr_user (id) ON DELETE CASCADE,
  CONSTRAINT fk_idr_user_source_mute_idr_source
    FOREIGN KEY (source_id) REFERENCES idr_source (id) ON UPDATE CASCADE ON DELETE CASCADE
);

CREATE INDEX idx_idr_user_source_mute_source_id_user_id
  ON idr_user_source_mute (source_id, user_id);

-- -----------------------------------------------------------------------------
-- 6. idol 自助申请与支持者聚合
-- -----------------------------------------------------------------------------

CREATE TABLE idr_idol_request (
  id uuid NOT NULL DEFAULT gen_random_uuid(),
  normalized_name varchar(128) NOT NULL,
  display_name varchar(128) NOT NULL,
  note varchar(500) NOT NULL DEFAULT '',
  status varchar(16) NOT NULL DEFAULT 'pending',
  reviewed_by uuid,
  reviewed_at timestamptz,
  review_note varchar(500) NOT NULL DEFAULT '',
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_idr_idol_request PRIMARY KEY (id),
  CONSTRAINT uk_idr_idol_request_normalized_name UNIQUE (normalized_name),
  CONSTRAINT fk_idr_idol_request_idr_admin_account
    FOREIGN KEY (reviewed_by) REFERENCES idr_admin_account (id) ON DELETE RESTRICT,
  CONSTRAINT ck_idr_idol_request_normalized_name CHECK (length(btrim(normalized_name)) > 0),
  CONSTRAINT ck_idr_idol_request_display_name CHECK (length(btrim(display_name)) > 0),
  CONSTRAINT ck_idr_idol_request_status CHECK (status IN ('pending', 'approved', 'rejected')),
  CONSTRAINT ck_idr_idol_request_review CHECK (
    (status = 'pending' AND reviewed_by IS NULL AND reviewed_at IS NULL)
    OR
    (status IN ('approved', 'rejected') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
  )
);

CREATE INDEX idx_idr_idol_request_status_created_at
  ON idr_idol_request (status, created_at DESC);

CREATE TABLE idr_idol_request_supporter (
  request_id uuid NOT NULL,
  user_id uuid NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_idr_idol_request_supporter PRIMARY KEY (request_id, user_id),
  CONSTRAINT fk_idr_idol_request_supporter_idr_idol_request
    FOREIGN KEY (request_id) REFERENCES idr_idol_request (id) ON DELETE CASCADE,
  CONSTRAINT fk_idr_idol_request_supporter_idr_user
    FOREIGN KEY (user_id) REFERENCES idr_user (id) ON DELETE CASCADE
);

CREATE INDEX idx_idr_idol_request_supporter_user_id_request_id
  ON idr_idol_request_supporter (user_id, request_id);

-- -----------------------------------------------------------------------------
-- 7. 表、字段和关键索引的数据库元数据注释；Navicat 可直接读取
-- -----------------------------------------------------------------------------

COMMENT ON TABLE idr_idol IS '偶像基础资料与启停状态；停用后不再向用户提供选择，但保留历史动态和既有关系';
COMMENT ON COLUMN idr_idol.id IS '偶像唯一业务标识；创建后保持稳定，可由 seed 或管理端生成';
COMMENT ON COLUMN idr_idol.name IS '偶像对用户展示的名称';
COMMENT ON COLUMN idr_idol.avatar IS '偶像头像地址；空字符串表示尚未配置';
COMMENT ON COLUMN idr_idol.bio IS '偶像简介；空字符串表示尚未配置';
COMMENT ON COLUMN idr_idol.enabled IS '是否启用；停用后不再出现在用户可选目录中';
COMMENT ON COLUMN idr_idol.created_at IS '记录创建时间';
COMMENT ON COLUMN idr_idol.updated_at IS '记录最后更新时间';
COMMENT ON COLUMN idr_idol.version IS '管理端乐观锁版本号；每次管理修改递增';

COMMENT ON TABLE idr_source IS '偶像动态抓取源配置及最近抓取运行状态；一个偶像可以配置多个来源';
COMMENT ON COLUMN idr_source.id IS '动态源唯一业务标识；创建后保持稳定';
COMMENT ON COLUMN idr_source.idol_id IS '所属偶像 ID，关联 idr_idol.id';
COMMENT ON COLUMN idr_source.rss_url IS '服务端实际抓取地址；属于内部配置，不得通过小程序 API 返回';
COMMENT ON COLUMN idr_source.channel IS '来源渠道标识，例如微博、抖音或 RSS';
COMMENT ON COLUMN idr_source.enabled IS '是否启用抓取；停用后 Worker 不再读取该源';
COMMENT ON COLUMN idr_source.last_fetch_at IS '最近一次抓取结束时间，无论成功或失败';
COMMENT ON COLUMN idr_source.last_fetch_status IS '最近一次抓取状态：never、success、failed';
COMMENT ON COLUMN idr_source.last_fetch_error_code IS '最近一次抓取失败错误码；成功时应为空';
COMMENT ON COLUMN idr_source.last_fetch_item_count IS '最近一次抓取成功解析出的条目数量';
COMMENT ON COLUMN idr_source.last_fetch_new_count IS '最近一次抓取中新入库的动态数量';
COMMENT ON COLUMN idr_source.created_at IS '记录创建时间';
COMMENT ON COLUMN idr_source.updated_at IS '配置或抓取状态最后更新时间';
COMMENT ON COLUMN idr_source.display_name IS '面向用户和管理员展示的来源名称，例如王一博后援会 · 微博';
COMMENT ON COLUMN idr_source.last_success_at IS '最近一次成功抓取时间；后续失败不得覆盖';
COMMENT ON COLUMN idr_source.consecutive_failures IS '连续抓取失败次数；成功后归零';
COMMENT ON COLUMN idr_source.version IS '管理端乐观锁版本号；每次管理修改递增';

COMMENT ON TABLE idr_post IS '从不同动态源抓取并归一化后的偶像动态；供动态流和推送使用';
COMMENT ON COLUMN idr_post.id IS '动态唯一业务标识；通常由源条目标识稳定生成';
COMMENT ON COLUMN idr_post.idol_id IS '动态所属偶像 ID，关联 idr_idol.id';
COMMENT ON COLUMN idr_post.source_id IS '动态所属抓取源 ID，关联 idr_source.id';
COMMENT ON COLUMN idr_post.channel IS '动态渠道标识，用于客户端展示来源标签';
COMMENT ON COLUMN idr_post.title IS '动态标题；不能为空';
COMMENT ON COLUMN idr_post.summary IS '动态摘要；源缺少摘要时保存空字符串';
COMMENT ON COLUMN idr_post.link IS '动态原文链接；现有单链接幂等约束的业务键';
COMMENT ON COLUMN idr_post.published_at IS '上游内容发布时间；今日动态等产品指标以此字段计算';
COMMENT ON COLUMN idr_post.fetched_at IS '本系统成功抓取该动态的时间';
COMMENT ON COLUMN idr_post.created_at IS '动态首次写入数据库的时间';
COMMENT ON COLUMN idr_post.dedup_key IS '跨来源稳定去重键；无法可靠生成时保持为空，禁止使用模糊标题强行合并';

COMMENT ON TABLE idr_user IS '微信小程序用户身份、当前兼容守护字段、订阅授权额度及首次转化时间';
COMMENT ON COLUMN idr_user.id IS '用户内部 UUID；所有客户端不得自行提交该值冒充其他用户';
COMMENT ON COLUMN idr_user.openid IS '微信小程序 OpenID；敏感身份信息，不得出现在管理看板和日志中';
COMMENT ON COLUMN idr_user.idol_id IS '兼容期单值守护偶像 ID；多守护迁移完成后由后续 contract migration 删除';
COMMENT ON COLUMN idr_user.guarding_since IS '兼容期当前偶像开始守护时间；多守护迁移完成后删除';
COMMENT ON COLUMN idr_user.subscribe_quota IS '当前微信订阅消息模板剩余可消费授权次数，范围 0 至 100';
COMMENT ON COLUMN idr_user.subscribed_at IS '最近一次成功确认订阅授权的时间';
COMMENT ON COLUMN idr_user.phone IS '预留手机号字段；当前小程序业务未使用，禁止在未授权情况下采集';
COMMENT ON COLUMN idr_user.billing IS '预留计费扩展 JSON；当前业务未使用，不得保存支付密钥等敏感值';
COMMENT ON COLUMN idr_user.created_at IS '用户首次通过微信登录创建账号的时间';
COMMENT ON COLUMN idr_user.updated_at IS '用户业务状态最后更新时间';
COMMENT ON COLUMN idr_user.subscribe_template_id IS '当前订阅额度对应的微信消息模板 ID；模板变化时旧额度不可复用';
COMMENT ON COLUMN idr_user.first_guarded_at IS '用户首次完成守护的时间；只允许首次写入，用于闭环转化统计';
COMMENT ON COLUMN idr_user.first_subscribed_at IS '用户首次完成订阅授权的时间；只允许首次写入，用于闭环转化统计';

COMMENT ON TABLE idr_user_session IS '小程序用户登录会话；只保存令牌哈希，不保存可直接使用的原始令牌';
COMMENT ON COLUMN idr_user_session.token_hash IS '登录令牌 SHA-256 小写十六进制哈希，同时作为会话主键';
COMMENT ON COLUMN idr_user_session.user_id IS '会话所属用户 ID，关联 idr_user.id';
COMMENT ON COLUMN idr_user_session.expires_at IS '会话失效时间；过期后不可继续访问 API';
COMMENT ON COLUMN idr_user_session.last_used_at IS '会话最近一次被服务端确认使用的时间，最多每五分钟更新一次';
COMMENT ON COLUMN idr_user_session.created_at IS '会话创建时间';

COMMENT ON TABLE idr_notification_delivery IS '用户与动态维度的微信订阅消息投递账本，负责幂等、额度预留、重试和回访归因';
COMMENT ON COLUMN idr_notification_delivery.post_id IS '被推送动态 ID，关联 idr_post.id';
COMMENT ON COLUMN idr_notification_delivery.user_id IS '接收推送用户 ID，关联 idr_user.id';
COMMENT ON COLUMN idr_notification_delivery.status IS '投递状态：reserved、sending、retryable、sent、failed、uncertain';
COMMENT ON COLUMN idr_notification_delivery.error_code IS '最近一次投递错误码；成功时为空';
COMMENT ON COLUMN idr_notification_delivery.created_at IS '投递记录创建时间';
COMMENT ON COLUMN idr_notification_delivery.updated_at IS '投递状态最后更新时间';
COMMENT ON COLUMN idr_notification_delivery.attempted_at IS '首次越过微信 HTTP 发送边界的时间';
COMMENT ON COLUMN idr_notification_delivery.finished_at IS '投递进入终态的时间';
COMMENT ON COLUMN idr_notification_delivery.template_id IS '本次投递使用的微信订阅消息模板 ID';
COMMENT ON COLUMN idr_notification_delivery.attempt_count IS '投递尝试次数，包含首次尝试';
COMMENT ON COLUMN idr_notification_delivery.next_attempt_at IS '处于 retryable 状态时的下次允许重试时间';
COMMENT ON COLUMN idr_notification_delivery.quota_reserved IS '是否已从用户额度中预占一次授权，用于失败恢复和防止重复扣减';
COMMENT ON COLUMN idr_notification_delivery.first_opened_at IS '用户首次通过该条推送进入小程序的时间';
COMMENT ON COLUMN idr_notification_delivery.last_opened_at IS '用户最近一次通过该条推送进入小程序的时间';
COMMENT ON COLUMN idr_notification_delivery.open_count IS '用户通过该条推送进入小程序的累计次数';

COMMENT ON TABLE idr_notification_outbox IS '动态入库后的事务出站队列，保证通知调度意图不会因进程崩溃而丢失';
COMMENT ON COLUMN idr_notification_outbox.idol_id IS '待处理通知所属偶像 ID；同时作为每偶像单行合并键';
COMMENT ON COLUMN idr_notification_outbox.post_id IS '该偶像当前等待推送的最新动态 ID';
COMMENT ON COLUMN idr_notification_outbox.status IS '任务状态：pending、processing、retryable、completed';
COMMENT ON COLUMN idr_notification_outbox.attempt_count IS 'Worker 领取该任务的累计次数';
COMMENT ON COLUMN idr_notification_outbox.next_attempt_at IS '任务下一次允许领取的时间';
COMMENT ON COLUMN idr_notification_outbox.lease_expires_at IS 'processing 任务租约失效时间；非 processing 状态必须为空';
COMMENT ON COLUMN idr_notification_outbox.error_code IS '最近一次任务处理错误码';
COMMENT ON COLUMN idr_notification_outbox.completed_at IS '任务最近一次成功完成时间';
COMMENT ON COLUMN idr_notification_outbox.created_at IS 'outbox 首次创建时间';
COMMENT ON COLUMN idr_notification_outbox.updated_at IS '任务状态或目标动态最后更新时间';

COMMENT ON TABLE idr_admin_account IS '管理后台管理员账号；与微信小程序用户身份完全隔离';
COMMENT ON COLUMN idr_admin_account.id IS '管理员内部 UUID';
COMMENT ON COLUMN idr_admin_account.username IS '管理员登录名；当前按大小写敏感方式唯一';
COMMENT ON COLUMN idr_admin_account.password_hash IS '管理员密码强哈希；禁止保存明文密码或可逆密文';
COMMENT ON COLUMN idr_admin_account.enabled IS '管理员是否可登录；停用后应同时撤销有效会话';
COMMENT ON COLUMN idr_admin_account.version IS '管理员账号乐观锁版本号';
COMMENT ON COLUMN idr_admin_account.last_login_at IS '最近一次成功登录时间';
COMMENT ON COLUMN idr_admin_account.created_at IS '管理员账号创建时间';
COMMENT ON COLUMN idr_admin_account.updated_at IS '管理员账号最后更新时间';

COMMENT ON TABLE idr_admin_session IS '管理后台登录会话；与小程序用户会话完全隔离';
COMMENT ON COLUMN idr_admin_session.token_hash IS '管理员登录令牌 SHA-256 小写十六进制哈希，同时作为会话主键';
COMMENT ON COLUMN idr_admin_session.admin_id IS '会话所属管理员 ID，关联 idr_admin_account.id';
COMMENT ON COLUMN idr_admin_session.expires_at IS '管理员会话失效时间';
COMMENT ON COLUMN idr_admin_session.last_used_at IS '管理员会话最近使用时间';
COMMENT ON COLUMN idr_admin_session.revoked_at IS '会话主动撤销时间；为空表示尚未撤销';
COMMENT ON COLUMN idr_admin_session.created_at IS '管理员会话创建时间';

COMMENT ON TABLE idr_admin_audit_log IS '管理后台写操作审计记录，用于追溯操作者、目标、结果和业务摘要';
COMMENT ON COLUMN idr_admin_audit_log.id IS '审计记录 UUID';
COMMENT ON COLUMN idr_admin_audit_log.admin_id IS '执行操作的管理员 ID';
COMMENT ON COLUMN idr_admin_audit_log.action IS '操作类型，例如 IDOL_UPDATE、SOURCE_DISABLE';
COMMENT ON COLUMN idr_admin_audit_log.resource_type IS '被操作资源类型，例如 idol、source、idol_request';
COMMENT ON COLUMN idr_admin_audit_log.resource_id IS '被操作资源业务 ID；无具体资源时为空';
COMMENT ON COLUMN idr_admin_audit_log.request_id IS '服务端请求追踪 ID，用于关联应用日志';
COMMENT ON COLUMN idr_admin_audit_log.detail IS '操作前后业务摘要 JSON；禁止写入密码、令牌、OpenID 和密钥';
COMMENT ON COLUMN idr_admin_audit_log.succeeded IS '操作是否成功；失败操作也可记录以支持安全审计';
COMMENT ON COLUMN idr_admin_audit_log.created_at IS '操作发生时间';

COMMENT ON TABLE idr_user_guard IS '用户与偶像的多守护关联；逐步替代 idr_user 上的单值 idol_id';
COMMENT ON COLUMN idr_user_guard.user_id IS '守护关系所属用户 ID';
COMMENT ON COLUMN idr_user_guard.idol_id IS '被守护偶像 ID';
COMMENT ON COLUMN idr_user_guard.guarding_since IS '该用户开始守护该偶像的时间';
COMMENT ON COLUMN idr_user_guard.created_at IS '守护关系创建时间';
COMMENT ON COLUMN idr_user_guard.updated_at IS '守护关系最后更新时间';

COMMENT ON TABLE idr_user_source_mute IS '用户关闭的动态源集合；没有记录表示默认接收该源的推送';
COMMENT ON COLUMN idr_user_source_mute.user_id IS '关闭来源的用户 ID';
COMMENT ON COLUMN idr_user_source_mute.source_id IS '被用户关闭的动态源 ID';
COMMENT ON COLUMN idr_user_source_mute.created_at IS '用户关闭该来源的时间';

COMMENT ON TABLE idr_idol_request IS '用户提交的 idol 入库申请主记录；相同规范化名称聚合为一条申请';
COMMENT ON COLUMN idr_idol_request.id IS 'idol 申请 UUID';
COMMENT ON COLUMN idr_idol_request.normalized_name IS '用于聚合重复申请的规范化名称';
COMMENT ON COLUMN idr_idol_request.display_name IS '用户提交并面向管理员展示的 idol 名称';
COMMENT ON COLUMN idr_idol_request.note IS '用户补充说明；为空字符串表示未填写';
COMMENT ON COLUMN idr_idol_request.status IS '审核状态：pending、approved、rejected';
COMMENT ON COLUMN idr_idol_request.reviewed_by IS '完成审核的管理员 ID；待审核时为空';
COMMENT ON COLUMN idr_idol_request.reviewed_at IS '审核完成时间；待审核时为空';
COMMENT ON COLUMN idr_idol_request.review_note IS '管理员审核备注或驳回原因';
COMMENT ON COLUMN idr_idol_request.created_at IS '申请首次创建时间';
COMMENT ON COLUMN idr_idol_request.updated_at IS '申请内容、支持者或审核状态最后更新时间';

COMMENT ON TABLE idr_idol_request_supporter IS 'idol 申请与支持用户关联；复合主键防止同一用户重复支持';
COMMENT ON COLUMN idr_idol_request_supporter.request_id IS '被支持的 idol 申请 ID';
COMMENT ON COLUMN idr_idol_request_supporter.user_id IS '支持该申请的用户 ID';
COMMENT ON COLUMN idr_idol_request_supporter.created_at IS '用户首次支持该申请的时间';

COMMENT ON INDEX idx_idr_idol_enabled_name IS '支持用户端按名称展示启用 idol';
COMMENT ON INDEX idx_idr_source_enabled_idol_id IS '支持 Worker 扫描启用源及按 idol 查询启用源';
COMMENT ON INDEX idx_idr_source_fetch_health IS '支持管理端按启用状态、抓取状态和最后成功时间筛选异常源';
COMMENT ON INDEX idx_idr_post_idol_id_published_at_id IS '支持动态流按偶像进行稳定游标分页';
COMMENT ON INDEX uk_idr_post_idol_id_dedup_key IS '同一 idol 下跨来源内容的最终并发去重防线';
COMMENT ON INDEX idx_idr_user_notification_targets IS '支持 Worker 只扫描仍有订阅额度的单 idol 通知目标';
COMMENT ON INDEX idx_idr_notification_delivery_reconciliation IS '支持 Worker 定位进程中断后遗留的发送状态';
COMMENT ON INDEX idx_idr_notification_delivery_retry IS '支持 Worker 按计划时间读取可重试投递';
COMMENT ON INDEX idx_idr_notification_outbox_due IS '支持 Worker 按到期时间领取待处理 outbox';
COMMENT ON INDEX idx_idr_notification_outbox_lease IS '支持 Worker 恢复租约已过期的 processing outbox';
COMMENT ON INDEX idx_idr_user_guard_idol_id_user_id IS '支持 Worker 按 idol 反向筛选守护用户';
COMMENT ON INDEX idx_idr_user_source_mute_source_id_user_id IS '支持推送目标筛选时按来源排除已屏蔽用户';
COMMENT ON INDEX idx_idr_idol_request_status_created_at IS '支持管理端按审核状态和时间查询 idol 申请';
