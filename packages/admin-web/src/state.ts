import type {
  AuditEntry,
  CoreMetrics,
  Delivery,
  DeliveryFailure,
  DeliveryQueue,
  DeliverySummary,
  Idol,
  IdolRequest,
  NotificationTarget,
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
  auditSearch: string;
  auditRangeHours: number;
  auditCursors: (string | null)[];
  auditNextCursor: string | null;
  idols: Idol[];
  sources: Source[];
  sourceSummary: SourceSummary;
  requests: IdolRequest[];
  pendingCount: number;
  deliveries: Delivery[];
  notificationTargets: NotificationTarget[];
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
  auditSearch: '',
  auditRangeHours: 24,
  // 栈顶是当前页游标；上一页直接出栈，无需 offset 或额外历史请求。
  auditCursors: [null],
  auditNextCursor: null,
  idols: [],
  sources: [],
  sourceSummary: {},
  requests: [],
  pendingCount: 0,
  deliveries: [],
  notificationTargets: [],
  deliverySummary: {},
  deliveryFailures: [],
  deliveryQueue: {},
  deliveryRange: 24,
  deliveryIdol: 'all',
  // null 表示尚未拉到指标：页面据此显示占位，而不是把 0 当成真实结果展示。
  metrics: null,
  metricsRange: 7,
  audits: [],
};
