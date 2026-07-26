# RSSHub 到 PostgreSQL 数据链路说明

## 1. 当前结论

代码、数据库迁移、幂等 seed、Worker 入库与通知 outbox 已通过单元测试和 PostgreSQL
Testcontainers 集成测试。服务器仍需用真实 Cookie 完成 RSSHub 到小程序通知的人工验收。

已确认：

- RSSHub `/weibo/user/5492443184` 实际抓取王一博微博成功，返回 HTTP 200 和 10 条动态。
- 后端已经实现 RSS 下载、解析、转换、去重、事务入库和数据源状态更新。
- RSSHub 微博转换测试及 PostgreSQL 17 集成测试已执行通过。
- 根 `compose.yaml` 已统一 RSSHub、数据库、Java API 与定时 Worker；HTTPS 由服务器宿主机 Nginx 提供。
- 微博 Cookie 仅保存在本机忽略文件中，不应提交到 Git。

因此目前的准确状态是：

```text
微博
  |
  v
RSSHub route             已真实验证
  |
  v
安全下载与 RSS 解析       已实现，已有测试用例
  |
  v
标准化 Post             已实现，已有测试用例
  |
  v
PostgreSQL 事务写入      已通过真实 PostgreSQL 17 容器测试
```

## 2. 数据链路

一次 Worker 运行会执行以下流程：

1. 从 `sources` 表读取全部启用的数据源。
2. 校验 Feed URL，阻止未授权的 HTTP、私网地址、危险 DNS 解析和不安全重定向。
3. 下载有限大小的 RSS XML。
4. 解析 RSS 2.0 或 Atom，并禁用 DTD、外部实体和外部 Schema。
5. 清洗标题和摘要，规范化链接和发布时间。
6. 使用规范化链接生成确定性 Post ID。
7. 将 Feed 条目转换成统一的 `posts` 记录。
8. 在同一事务内写入 Post 和通知 outbox。
9. 更新数据源最后抓取时间、状态、条目数、新增数和错误码。

主要实现：

- `backend/src/main/java/com/idolradar/worker/WorkerService.java`
- `backend/src/main/java/com/idolradar/worker/WorkerStore.java`
- `backend/src/main/java/com/idolradar/worker/ApacheFeedDownloader.java`
- `backend/src/main/java/com/idolradar/worker/FeedUrlGuard.java`
- `backend/src/main/java/com/idolradar/worker/FeedParser.java`

数据库通过以下机制保证幂等和一致性：

- `posts.link` 全局唯一，阻止同一动态重复入库。
- `INSERT ... ON CONFLICT DO NOTHING` 允许 Worker 安全重复运行。
- PostgreSQL advisory lock 阻止多个 `fetch-feeds` Worker 重叠执行。
- Post 与通知 outbox 在同一事务写入，避免 Post 成功但通知意图丢失。
- 每个 Idol 的 outbox 只保留最新 Post，避免短时间多条动态造成重复通知。

## 3. 更换或新增 Idol

系统的数据模型和 Worker 均支持多个 Idol，不与王一博写死绑定。

### 3.1 新增 Idol

在 `database/idols.seed.jsonl` 添加：

```json
{"_id":"idol_new","name":"新偶像","avatar":"","bio":"微博动态聚合。","enabled":true}
```

在 `database/sources.seed.jsonl` 添加：

```json
{"_id":"source_new_weibo","idolId":"idol_new","rsshubRoute":"/weibo/user/<微博UID>","channel":"微博","enabled":true,"lastFetchStatus":"never"}
```

然后重新执行幂等 seed，并立即运行一次 Worker：

```powershell
docker compose run --rm seed
docker compose run --rm -e IDOLRADAR_WORKER_SCHEDULE_ENABLED=false worker
```

注意事项：

- 微博 route 使用数字 UID，不是微博昵称。
- 部分微博账号需要有效登录 Cookie。
- 使用其他平台时，将 `rsshubRoute` 改为对应 RSSHub route。
- `_id` 必须稳定且全局唯一，避免重复创建或错误覆盖。
- RSSHub route 需要先单独访问验证，确认能够返回合法 RSS。

### 3.2 替换或停用 Idol

删除 seed 文件中的记录不会删除数据库现存记录，因为 seed 采用幂等 upsert，不执行同步
删除。

停止抓取某个 Idol 时，应将其所有 source 设置为：

```json
{"enabled":false}
```

当前 Worker 只检查 `sources.enabled`，不检查 `idols.enabled`。因此仅设置
`idols.enabled=false` 仍可能继续抓取。后续应修改查询，同时要求 Idol 和 source 均为
启用状态。

## 4. 自动化程度

### 4.1 已自动化部分

Worker 每次启动后，以下操作无需人工介入：

- 遍历启用的数据源。
- 并发下载 Feed。
- 解析和标准化 RSS 数据。
- 去重并写入 PostgreSQL。
- 写入通知 outbox。
- 更新抓取状态。
- 隔离单个 source 故障，继续处理其他 source。

### 4.2 尚未自动化部分

`worker` 容器常驻，Spring 默认每 30 分钟固定延迟运行。间隔由 `WORKER_INTERVAL` 配置；
异常不会终止后续轮次。PostgreSQL advisory lock 继续防止多实例重入。

## 5. 链路完整性评估

当前 MVP 代码完整度约为 80%。

### 5.1 已具备能力

- RSSHub 实例地址与 route 分离，同一 seed 可用于本机和生产环境。
- 支持多个 Idol、多个平台和多个 source。
- 支持 RSS 2.0 和 Atom。
- 支持抓取超时、响应大小和重定向次数限制。
- 支持 SSRF 防护、DNS pin 和重定向复验。
- 支持 XXE 和危险 XML 声明防护。
- 支持标题、摘要、链接和发布时间标准化。
- 支持 source 级并发抓取。
- 支持数据库唯一约束和幂等插入。
- 支持 Worker 全局 advisory lock。
- 支持 Post 与通知 outbox 事务一致性。
- 支持 source 抓取成功、失败和统计状态。
- 本地可关闭微信通知，仅验证 RSS 入库。

### 5.2 主要缺口

- 尚未在目标服务器用真实 Cookie 完成 RSSHub、Worker、微信通知全链路验收。
- Cookie 失效没有自动检测、轮换或告警。
- 已存在 Post 使用冲突忽略，微博编辑后不会更新数据库内容。
- 微博删除后，数据库历史 Post 不会自动删除或标记。
- 只保存清洗后的标题和摘要，不保存图片、完整 HTML 或原始 Feed 条目。
- `idols.enabled=false` 不会自动阻止其 source 被抓取。
- 缺少连续失败告警、抓取延迟指标和数据新鲜度监控。
- 首次回填和后续增量抓取没有显式区分。

## 6. 改进建议

### P0：闭环验收

增加真正的端到端集成测试：

1. 启动 PostgreSQL。
2. 启动测试 RSSHub 或稳定的本地 RSS HTTP Server。
3. 执行 migration 和 seed。
4. 执行一次 Worker，确认写入预期 Post。
5. 再执行一次 Worker，确认新增数为 0。
6. 校验 `sources.last_fetch_*` 和通知 outbox。

真实微博适合作为人工冒烟测试，不适合作为每次 CI 的强依赖；CI 应使用固定 Feed fixture，
避免微博限流、Cookie 失效或上游内容变化造成随机失败。

### P0：生产调度告警

- 监控 Worker 容器存活、最近成功时间及连续失败次数。
- 对 Worker 非零退出、全部 source 失败、连续失败和长时间无成功抓取告警。
- 监控最后成功时间、抓取耗时、Feed 条目数和新增 Post 数。
- 调度周期加入少量随机抖动，降低多个实例同时访问上游的风险。

### P0：Cookie 安全与生命周期

- 生产 Cookie 使用 Secret Manager、Kubernetes Secret 或受控环境变量。
- 禁止将 Cookie 写入代码、seed、日志或 Git。
- 检测 RSSHub 返回的登录失效、403、空 Feed 等异常。
- 在 Cookie 到期前告警并完成轮换。

### P1：启用状态一致性

将启用源查询改为同时检查：

```sql
sources.enabled = TRUE AND idols.enabled = TRUE
```

同时保留 source 独立开关，允许只停用某个平台而不停用整个 Idol。

### P1：内容更新与删除策略

当前按照链接去重，适合只追加的 MVP，但无法处理微博编辑和删除。

建议增加：

- `external_id`：平台原始动态 ID。
- `content_hash`：检测标题、摘要或正文是否变化。
- `updated_at`：记录上游内容更新时间。
- `deleted_at` 或 `source_status`：按产品需求标记已删除内容。

发现同一 `external_id` 内容变化时执行受控更新，而不是直接忽略冲突。

### P1：内容保真

如果小程序需要展示图片或完整正文，建议保存：

- 清洗后的纯文本摘要。
- 图片 URL 列表。
- 允许的富文本结构。
- 原始 Feed 条目 JSON 或 XML 片段，可设置有限保留期。

原始数据有助于排错和重新解析，但应限制大小，并避免直接向客户端输出未经清洗的 HTML。

### P1：首次回填策略

首次接入一个 source 时，RSSHub 通常返回多条历史动态。建议区分：

- 回填数据：写入数据库，但不发送通知。
- 增量数据：正常写入并进入通知 outbox。

可在 `sources` 增加初始化状态或通知起始时间，避免首次接入时把历史动态当作实时更新。

### P2：抓取效率

- 保存 ETag 和 Last-Modified，支持条件请求。
- 为不同平台或 source 配置独立抓取周期。
- 根据连续无更新次数动态降低抓取频率。
- 保留 RSSHub 缓存，避免多个消费端重复访问微博。
- 限制单 source 每轮最大条目数和并发数，降低数据库及上游压力。

## 7. 建议验收标准

链路满足以下条件后，才建议标记为“端到端已完成”：

- RSSHub route 连续多次返回合法 Feed。
- seed 后数据库存在正确 Idol 和 source。
- Worker 首次运行写入预期 Post。
- Worker 第二次运行无重复 Post。
- source 状态正确记录成功、条目数和新增数。
- 单个 source 失败不影响其他 source。
- Worker 并发运行时只有一个实例实际抓取。
- 本地通知关闭时不要求微信密钥。
- Cookie 失效、Feed 超时和非法 XML 能产生明确错误状态。
- 周期调度已部署，并有连续失败告警。
- Docker 私网 RSSHub 可使用内部 HTTP；跨主机访问时必须使用 HTTPS。Cookie 由安全的
  密钥系统管理。
