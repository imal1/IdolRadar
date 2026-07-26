-- V4 清理早期部署导入的“林晚/苏念”演示目录，防止生产小程序继续展示虚构数据。
-- 必须在 V1-V3 后、正式 seed 前执行；仅匹配固定 demo ID，不按名称模糊删除真实数据。
-- 先删 post 以级联清理 delivery/outbox，再删 source；用户账号保留并解除旧守护关系。
DELETE FROM posts
WHERE idol_id IN ('idol_demo_lin_wan', 'idol_demo_su_nian');

DELETE FROM sources
WHERE id IN ('source_demo_lin_wan_official', 'source_demo_su_nian_official');

UPDATE users
SET idol_id = NULL,
    guarding_since = NULL,
    updated_at = now()
WHERE idol_id IN ('idol_demo_lin_wan', 'idol_demo_su_nian');

DELETE FROM idols
WHERE id IN ('idol_demo_lin_wan', 'idol_demo_su_nian');
