-- V8：在会话上记录签发这条会话的客户端类型，用于服务端按客户端执行守护数量上限（#30、ADR-0002）。
-- 目的：客户端类型不能由请求头等客户端可控输入决定，否则用户可以伪造出更高的上限。
--      微信登录端点用一次性 code 向微信换取 openid，这条路径本身即证明调用方是小程序，
--      因此类型在签发会话时写死，请求侧只读不写。
-- 部署顺序：可先于 app 升级执行。旧版 app 镜像的 INSERT 不带 client_type，由默认值补齐，
--          而旧镜像本身不读该列，因此迁移与旧容器可以共存，不需要停服。
-- 锁与耗时：PostgreSQL 11 起「加带默认值的列」只改系统目录、不重写堆表，耗时与行数无关；
--          ACCESS EXCLUSIVE 锁只在极短时间内持有。

ALTER TABLE idr_user_session
  ADD COLUMN client_type TEXT NOT NULL DEFAULT 'wechat-miniprogram';

-- 存量会话全部来自微信小程序，默认值即为真实值，无需回填。

COMMENT ON COLUMN idr_user_session.client_type IS
  '签发该会话的客户端类型，服务端据此执行守护数量上限；由登录端点写入，不接受客户端传入';
