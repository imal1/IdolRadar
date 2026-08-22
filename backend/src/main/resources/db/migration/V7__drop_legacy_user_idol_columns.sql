-- V7 expand-contract 收尾：删除 idr_user 上的单值守护字段，让 idr_user_guard 成为守护关系的唯一事实来源。
-- 部署顺序：必须在 #27（Worker/推送读路径）与 #28（API 读路径）都已上线并稳定运行之后执行。
--          本版本执行后，仍在写旧字段的上一版 app 镜像调用「更换守护对象」会直接报列不存在，
--          因此发布时要先 `docker compose stop app worker` 停掉旧容器，再 `docker compose up -d`
--          让 migrate/seed/app 依次起来；直接对运行中的旧容器执行 up -d 会留下一个数秒的写失败窗口。
-- 不可逆说明：DROP COLUMN 丢弃列数据，Flyway 无回滚脚本。
--            回滚前置条件是先从 idr_user_guard 重建该列（ADD COLUMN + 回填 + 重建索引与外键），
--            且回滚窗口内不得有新用户更换守护对象，否则重建出来的旧字段与关联表不一致。
-- 锁与耗时：DROP INDEX 与 DROP COLUMN 在 PostgreSQL 中都只改系统目录、不重写堆表，
--          耗时与 idr_user 行数无关（旧列的数据由后续 VACUUM 惰性回收），因此生产数据规模不影响执行时间；
--          真正的风险是两者都取 ACCESS EXCLUSIVE 锁并会被长事务阻塞，故须在低流量窗口执行。

-- 这两个索引的前导列都是即将删除的 idol_id，删列会连带删除它们；
-- 显式先删是为了让「本次移除了哪些索引」在迁移历史里可读，而不是作为副作用消失。
DROP INDEX idx_idr_user_idol_id_subscribe_quota_id;
DROP INDEX idx_idr_user_notification_targets;

-- 外键 fk_idr_user_idr_idol 随列一起删除；守护关系的引用完整性已由 idr_user_guard 上的同名外键保证。
ALTER TABLE idr_user
  DROP COLUMN idol_id,
  DROP COLUMN guarding_since;

COMMENT ON TABLE idr_user IS '微信小程序用户身份、订阅授权额度及首次转化时间；守护关系见 idr_user_guard';
