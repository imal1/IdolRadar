import type {
  AuditEntry,
  CoreMetrics,
  Delivery,
  DeliveryFailure,
  DeliveryQueue,
  DeliverySummary,
  Idol,
  IdolRequest,
  Source,
  SourceSummary,
} from './types';

export type PageId = 'dashboard' | 'idols' | 'sources' | 'deliveries' | 'requests' | 'audit';

/**
 * 管理端唯一的可变状态。
 *
 * <p>页面模块只读它、只渲染，写入集中在 main.ts 的加载器与筛选处理里：
 * 全量重绘模型下，多处写状态会让「界面显示的条件」和「服务端聚合用的条件」悄悄分叉。
 */
export interface AdminState {
  page: PageId;
  deliveryFilter: string;
  requestFilter: string;
  sourceStatus: string;
  idolStatus: string;
  auditResult: string;
  idols: Idol[];
  sources: Source[];
  sourceSummary: SourceSummary;
  requests: IdolRequest[];
  pendingCount: number;
  deliveries: Delivery[];
  deliverySummary: DeliverySummary;
  deliveryFailures: DeliveryFailure[];
  deliveryQueue: DeliveryQueue;
  deliveryRange: number;
  deliveryIdol: string;
  metrics: CoreMetrics | null;
  metricsRange: number;
  audits: AuditEntry[];
}

export const state: AdminState = {
  page: 'dashboard',
  deliveryFilter: 'all',
  requestFilter: 'pending',
  sourceStatus: 'all',
  idolStatus: 'all',
  auditResult: 'all',
  idols: [],
  sources: [],
  sourceSummary: {},
  requests: [],
  pendingCount: 0,
  deliveries: [],
  deliverySummary: {},
  deliveryFailures: [],
  deliveryQueue: {},
  deliveryRange: 24,
  deliveryIdol: 'all',
  // null 表示尚未拉到指标：页面据此显示占位，而不是把 0 当成真实结果展示。
  metrics: null,
  metricsRange: 7,
  // 审计日志仍为演示数据，接真实接口是独立 issue。
  audits: [
    { id: 'AUD-00816', operator: '管理员', action: 'UPDATE_SOURCE', resource: 'idr_source#13', result: 'success', requestId: 'req-3a9f71', summary: '更新来源 RSS 地址', time: '今天 11:06:22', before: '{"rss_url":"https://old.example.com/route"}', after: '{"rss_url":"https://rss.example.com/weibo/user/guard-club"}' },
    { id: 'AUD-00815', operator: '管理员', action: 'TRIGGER_FETCH', resource: 'idr_source#13', result: 'success', requestId: 'req-3a9e82', summary: '手动验证来源，不产生推送', time: '今天 10:52:18', before: '{}', after: '{"parsed":20,"inserted":0,"status":"SUCCESS"}' },
    { id: 'AUD-00814', operator: '管理员', action: 'REVIEW_IDOL_REQUEST', resource: 'idr_idol_request#204', result: 'success', requestId: 'req-3a9d11', summary: '通过申请并关联正式 idol', time: '今天 10:31:44', before: '{"status":"PENDING"}', after: '{"status":"APPROVED","approved_idol_id":5}' },
    { id: 'AUD-00813', operator: '管理员', action: 'DISABLE_IDOL', resource: 'idr_idol#4', result: 'success', requestId: 'req-3a9c09', summary: '停用 idol，保留历史业务数据', time: '今天 09:18:03', before: '{"enabled":true}', after: '{"enabled":false,"version":2}' },
    { id: 'AUD-00812', operator: '管理员', action: 'LOGIN', resource: 'idr_admin_account#1', result: 'success', requestId: 'req-3a9b42', summary: '管理员登录', time: '今天 08:58:11', before: '{}', after: '{}' },
    { id: 'AUD-00811', operator: '管理员', action: 'UPDATE_IDOL', resource: 'idr_idol#2', result: 'failed', requestId: 'req-3a9a67', summary: '版本冲突，修改未保存', time: '昨天 22:40:09', before: '{"version":3}', after: '{"expected_version":4}' },
  ],
};
