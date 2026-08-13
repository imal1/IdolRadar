/** 管理端 API 响应的手写类型；字段以 `/admin/v1/**` 实际返回为准，只声明管理端消费到的部分。 */

export type SourceHealth = 'healthy' | 'failed' | 'stale' | 'waiting' | 'disabled';

export type RequestStatus = 'pending' | 'approved' | 'rejected';

export interface AdminProfile {
  username: string;
}

export interface LoginResult {
  token: string;
  admin: AdminProfile;
}

export interface Idol {
  id: string;
  name: string;
  bio: string | null;
  avatar: string | null;
  enabled: boolean;
  sourceCount: number;
  guardCount: number;
  version: number;
  updatedAt: string | null;
}

export interface Source {
  id: string;
  idolId: string;
  idolName: string;
  displayName: string;
  channel: string;
  rssUrl: string;
  enabled: boolean;
  health: SourceHealth;
  lastFetchAt: string | null;
  lastSuccessAt: string | null;
  lastFetchItemCount: number;
  lastFetchNewCount: number;
  lastFetchErrorCode: string | null;
  consecutiveFailures: number;
  version: number;
}

export interface SourceSummary {
  healthy?: number;
  failed?: number;
  stale?: number;
  waiting?: number;
}

export interface IdolRequest {
  id: string;
  displayName: string;
  normalizedName: string;
  supporterCount: number;
  note: string | null;
  createdAt: string | null;
  status: RequestStatus;
  reviewer: string | null;
  reviewedAt: string | null;
  reviewNote: string | null;
  approvedIdolId: string | null;
}

export interface Delivery {
  postId: string;
  postTitle: string | null;
  userId: string;
  idolId: string;
  idolName: string;
  status: string;
  errorCode: string | null;
  attemptCount: number;
  createdAt: string | null;
  attemptedAt: string | null;
  finishedAt: string | null;
  nextAttemptAt: string | null;
  openCount: number;
  firstOpenedAt: string | null;
}

/** 状态分布同时用于筛选标签计数，因此按状态名索引；`successRate`、`openRate` 是百分比整数。 */
export interface DeliverySummary {
  [status: string]: number | undefined;
}

export interface DeliveryQueue {
  backlog?: number;
  pending?: number;
  processing?: number;
  retryable?: number;
  oldestQueuedAt?: string | null;
}

export interface DeliveryFailure {
  errorCode: string;
  total: number;
}

export interface DeliveryBoard {
  deliveries: Delivery[];
  summary: DeliverySummary;
  failures: DeliveryFailure[];
  queue: DeliveryQueue;
}

export interface VerifyResult {
  ok: boolean;
  itemCount: number;
  newCount: number;
  errorCode: string | null;
  samples?: { title: string; known: boolean }[];
}

/** 审计日志页仍为演示数据，接真实接口是独立 issue。 */
export interface AuditEntry {
  id: string;
  operator: string;
  action: string;
  resource: string;
  result: 'success' | 'failed';
  requestId: string;
  summary: string;
  time: string;
  before: string;
  after: string;
}
