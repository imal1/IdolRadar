-- V1 建立偶像目录、动态抓取、用户、会话、通知的初始持久化模型。
-- 必须先于 API/Worker 部署；已执行版本不可修改，后续结构变更只能新增 migration。
-- UUID 由数据库生成，保证不同 API/Worker 实例的插入行为一致。
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- 管理员维护的偶像目录。停用只影响展示，保留历史动态与用户关系。
CREATE TABLE idols (
  id varchar(128) PRIMARY KEY,
  name text NOT NULL CHECK (length(btrim(name)) > 0),
  avatar text NOT NULL DEFAULT '',
  bio text NOT NULL DEFAULT '',
  enabled boolean NOT NULL DEFAULT true,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- 分别支持启用目录按名称展示、按稳定 ID 查询。
CREATE INDEX idols_enabled_name_idx ON idols (enabled, name);
CREATE INDEX idols_enabled_id_idx ON idols (enabled, id);

-- RSS 源配置及抓取运行状态。限制删除父记录，避免动态来源链断裂。
CREATE TABLE sources (
  id varchar(128) PRIMARY KEY,
  idol_id varchar(128) NOT NULL REFERENCES idols (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  rss_url text NOT NULL CHECK (length(btrim(rss_url)) > 0),
  channel varchar(32) NOT NULL DEFAULT 'RSS',
  enabled boolean NOT NULL DEFAULT true,
  last_fetch_at timestamptz,
  last_fetch_status varchar(16) NOT NULL DEFAULT 'never'
    CHECK (last_fetch_status IN ('never', 'success', 'failed')),
  last_fetch_error_code varchar(64),
  last_fetch_item_count integer NOT NULL DEFAULT 0 CHECK (last_fetch_item_count >= 0),
  last_fetch_new_count integer NOT NULL DEFAULT 0 CHECK (last_fetch_new_count >= 0),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- 支持 Worker 扫描启用源、按偶像查询源，避免全表扫描。
CREATE INDEX sources_enabled_idol_id_idx ON sources (enabled, idol_id);
CREATE INDEX sources_enabled_id_idx ON sources (enabled, id);
CREATE INDEX sources_idol_id_id_idx ON sources (idol_id, id);
CREATE INDEX sources_idol_id_enabled_id_idx ON sources (idol_id, enabled, id);

-- 归一化动态。全局 link 唯一约束是跨 RSS 源去重的最终幂等防线。
CREATE TABLE posts (
  id varchar(128) PRIMARY KEY,
  idol_id varchar(128) NOT NULL REFERENCES idols (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  source_id varchar(128) NOT NULL REFERENCES sources (id) ON UPDATE CASCADE ON DELETE RESTRICT,
  channel varchar(32) NOT NULL DEFAULT 'RSS',
  title text NOT NULL CHECK (length(btrim(title)) > 0),
  summary text NOT NULL DEFAULT '',
  link text NOT NULL UNIQUE CHECK (length(btrim(link)) > 0),
  published_at timestamptz NOT NULL,
  fetched_at timestamptz NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
);

-- 第一个索引服务稳定游标分页；第二个支持按源查询和故障排查。
CREATE INDEX posts_idol_id_published_at_id_idx
  ON posts (idol_id, published_at DESC, id DESC);
CREATE INDEX posts_source_id_published_at_idx
  ON posts (source_id, published_at DESC);

-- 每个微信身份一行、一个当前守护关系、有限且可消费的订阅额度。
-- 偶像目录被移除时使用 ON DELETE SET NULL，用户账号仍然有效。
CREATE TABLE users (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  openid varchar(128) NOT NULL UNIQUE,
  idol_id varchar(128) REFERENCES idols (id) ON UPDATE CASCADE ON DELETE SET NULL,
  guarding_since timestamptz,
  subscribe_quota integer NOT NULL DEFAULT 0 CHECK (subscribe_quota BETWEEN 0 AND 100),
  subscribed_at timestamptz,
  phone varchar(32),
  billing jsonb,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now()
);

-- 按偶像筛选通知目标；部分索引排除已无授权额度的用户。
CREATE INDEX users_idol_id_subscribe_quota_id_idx
  ON users (idol_id, subscribe_quota, id);
CREATE INDEX users_notification_targets_idx
  ON users (idol_id, id)
  WHERE subscribe_quota > 0;

-- 服务端会话只保存 token 哈希。过期索引用于清理，用户索引用于撤销会话。
CREATE TABLE sessions (
  token_hash char(64) PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
  user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  expires_at timestamptz NOT NULL,
  last_used_at timestamptz NOT NULL DEFAULT now(),
  created_at timestamptz NOT NULL DEFAULT now(),
  CHECK (expires_at > created_at)
);

CREATE INDEX sessions_user_id_idx ON sessions (user_id);
CREATE INDEX sessions_expires_at_idx ON sessions (expires_at);

-- 通知投递账本。复合主键保证不同重试/Worker 对同一 post-user 组合幂等。
CREATE TABLE notification_deliveries (
  post_id varchar(128) NOT NULL REFERENCES posts (id) ON UPDATE CASCADE ON DELETE CASCADE,
  user_id uuid NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  status varchar(16) NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending', 'sent', 'failed')),
  error_code varchar(64),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (post_id, user_id)
);

-- 支持用户投递历史，以及按状态处理、对账。
CREATE INDEX notification_deliveries_user_id_created_at_idx
  ON notification_deliveries (user_id, created_at DESC);
CREATE INDEX notification_deliveries_status_created_at_idx
  ON notification_deliveries (status, created_at);
