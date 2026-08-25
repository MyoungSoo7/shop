import api from './axios';

// ════════════════════════════════════════════════════════════════════════════
// operation-service (운영 관제) — /api/ops
//   게이트웨이가 /api/ops/** → operation-service(8092) 로 라우팅.
//   JWT ROLE_ADMIN 전용 (axios 인터셉터가 Bearer 토큰 자동 첨부).
// ════════════════════════════════════════════════════════════════════════════

export type IncidentStatus = 'OPEN' | 'ACKNOWLEDGED' | 'RESOLVED' | 'FALSE_POSITIVE';
export type IncidentSeverity = 'CRITICAL' | 'WARNING' | 'INFO';
export type SignalCategory =
  | 'ORDER_FAILURE' | 'PAYMENT_FAILURE' | 'STOCK_SHORTAGE' | 'SHIPPING_DELAY'
  | 'SETTLEMENT_FAILURE' | 'KAFKA_BACKLOG' | 'REDIS_FAILURE' | 'DB_DEADLOCK'
  | 'API_TIMEOUT' | 'INFRA_ETC' | 'UNKNOWN';

/** 인시던트 목록 항목 (GET /api/ops/incidents content[]). */
export interface Incident {
  id: number;
  correlationKey: string;
  source: string;              // ALERTMANAGER / ANOMALY / MANUAL
  category: SignalCategory;
  severity: IncidentSeverity;
  status: IncidentStatus;
  title: string;
  service: string | null;
  firstSeenAt: string;
  lastSeenAt: string;
  occurrenceCount: number;
  acknowledgedBy: string | null;
  acknowledgedAt: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
}

export interface TimelineEntry {
  eventType: string;           // OPENED/REFIRED/ACKNOWLEDGED/RESOLVED/AUTO_RESOLVED/FALSE_POSITIVE/COMMENT
  actor: string;
  note: string | null;
  createdAt: string;
}

/** 인시던트 단건 상세 (GET /api/ops/incidents/{id}). */
export interface IncidentDetail {
  incident: Incident;
  description: string | null;
  labels: Record<string, string>;
  annotations: Record<string, string>;
  timeline: TimelineEntry[];
}

/** 목록 페이지 응답. */
export interface IncidentPage {
  content: Incident[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/** 대시보드 요약 (GET /api/ops/incidents/summary). */
export interface IncidentSummary {
  window: string;
  openTotal: number;
  byStatus: Record<string, number>;
  byCategory: Record<string, number>;
  bySeverity: Record<string, number>;
  mttrSeconds: number | null;
}

export interface IncidentSearchParams {
  status?: IncidentStatus;
  category?: SignalCategory;
  severity?: IncidentSeverity;
  from?: string;    // ISO-8601 (firstSeenAt 기준)
  to?: string;
  page?: number;
  size?: number;
}

export type SummaryWindow = '1h' | '24h' | '7d';

export const operationApi = {
  /** GET /api/ops/incidents — 목록(필터 + 페이징, lastSeenAt DESC 고정). */
  search: async (params: IncidentSearchParams = {}): Promise<IncidentPage> => {
    const query: Record<string, unknown> = {};
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') query[k] = v;
    });
    return (await api.get<IncidentPage>('/api/ops/incidents', { params: query })).data;
  },

  /** GET /api/ops/incidents/{id} — 단건 + 타임라인. */
  get: async (id: number): Promise<IncidentDetail> =>
    (await api.get<IncidentDetail>(`/api/ops/incidents/${id}`)).data,

  /** GET /api/ops/incidents/summary — 대시보드 요약 카운트. */
  summary: async (window: SummaryWindow = '24h'): Promise<IncidentSummary> =>
    (await api.get<IncidentSummary>('/api/ops/incidents/summary', { params: { window } })).data,

  /** POST /api/ops/incidents/{id}/ack — 확인 처리. */
  acknowledge: async (id: number, note?: string): Promise<IncidentDetail> =>
    (await api.post<IncidentDetail>(`/api/ops/incidents/${id}/ack`, { note: note ?? null })).data,

  /** POST /api/ops/incidents/{id}/resolve — 수동 해제. */
  resolve: async (id: number, note?: string): Promise<IncidentDetail> =>
    (await api.post<IncidentDetail>(`/api/ops/incidents/${id}/resolve`, { note: note ?? null })).data,

  /** POST /api/ops/incidents/{id}/false-positive — 오탐 처리. */
  markFalsePositive: async (id: number, note?: string): Promise<IncidentDetail> =>
    (await api.post<IncidentDetail>(`/api/ops/incidents/${id}/false-positive`, { note: note ?? null })).data,

  /** POST /api/ops/incidents/{id}/comments — 코멘트 추가. */
  comment: async (id: number, note: string): Promise<IncidentDetail> =>
    (await api.post<IncidentDetail>(`/api/ops/incidents/${id}/comments`, { note })).data,
};

// ── 표시용 메타 (색상/한글 라벨) ────────────────────────────────────────────

export const STATUS_LABEL: Record<IncidentStatus, string> = {
  OPEN: '열림',
  ACKNOWLEDGED: '확인됨',
  RESOLVED: '해결됨',
  FALSE_POSITIVE: '오탐',
};

export const STATUS_BADGE: Record<IncidentStatus, string> = {
  OPEN: 'bg-red-100 text-red-800',
  ACKNOWLEDGED: 'bg-amber-100 text-amber-800',
  RESOLVED: 'bg-green-100 text-green-800',
  FALSE_POSITIVE: 'bg-gray-100 text-gray-600',
};

export const SEVERITY_LABEL: Record<IncidentSeverity, string> = {
  CRITICAL: '심각',
  WARNING: '경고',
  INFO: '정보',
};

export const SEVERITY_BADGE: Record<IncidentSeverity, string> = {
  CRITICAL: 'bg-red-600 text-white',
  WARNING: 'bg-amber-500 text-white',
  INFO: 'bg-sky-500 text-white',
};

export const CATEGORY_LABEL: Record<SignalCategory, string> = {
  ORDER_FAILURE: '주문 실패',
  PAYMENT_FAILURE: '결제/환불 실패',
  STOCK_SHORTAGE: '재고 부족',
  SHIPPING_DELAY: '배송 지연',
  SETTLEMENT_FAILURE: '정산 실패',
  KAFKA_BACKLOG: 'Kafka 적체',
  REDIS_FAILURE: 'Redis 장애',
  DB_DEADLOCK: 'DB 데드락',
  API_TIMEOUT: 'API 지연',
  INFRA_ETC: '기타 인프라',
  UNKNOWN: '미분류',
};

// ════════════════════════════════════════════════════════════════════════════
// 알림 발송 이력 — /api/ops/notifications/dispatches
//   "그 사람한테 알림이 갔나?" 를 조회로 답하는 경로. 같은 ROLE_ADMIN 체인.
// ════════════════════════════════════════════════════════════════════════════

/**
 * 발송 상태.
 * - PARTIAL 은 <b>실패가 아니다</b> — 일부 채널만 성공했어도 사람에게는 닿았다.
 * - NO_CHANNEL 은 메시지 문제가 아니라 배포 설정 오류(활성 채널 0개)라 대응 주체가 다르다.
 * - PENDING 이 남아 있다는 것은 발송 도중 프로세스가 죽었다는 뜻이고, 자동 복구되지 않는다.
 */
export type DispatchStatus = 'PENDING' | 'DELIVERED' | 'PARTIAL' | 'FAILED' | 'NO_CHANNEL';

/** 목록 행 — 본문은 상세에서만 온다. */
export interface NotificationDispatch {
  id: number;
  eventId: string;
  type: string;
  recipient: string;
  subject: string;
  status: DispatchStatus;
  channelsTotal: number;
  channelsSucceeded: number;
  resentFromId: number | null;
  createdAt: string;
  completedAt: string | null;
}

export interface DispatchChannelOutcome {
  channel: string;
  status: 'SUCCESS' | 'FAILURE';
  attempts: number;
  error: string | null;
  createdAt: string | null;
}

export interface NotificationDispatchDetail extends NotificationDispatch {
  body: string | null;
  channels: DispatchChannelOutcome[];
}

export interface NotificationDispatchPage {
  items: NotificationDispatch[];
  total: number;
  limit: number;
  offset: number;
}

export interface NotificationResendResult {
  originalId: number;
  eventId: string;
  /** 같은 idempotencyKey 로 이미 보낸 건이라 실제로는 나가지 않았다는 뜻 — 실패가 아니다. */
  deduped: boolean;
  allSucceeded: boolean;
  results: DispatchChannelOutcome[];
}

export interface DispatchSearchParams {
  status?: DispatchStatus;
  recipient?: string;
  limit?: number;
  offset?: number;
}

export const notificationDispatchApi = {
  /** GET /api/ops/notifications/dispatches — 최신순 목록(상태·수신자 정확일치 필터). */
  search: async (params: DispatchSearchParams = {}): Promise<NotificationDispatchPage> => {
    const query: Record<string, unknown> = {};
    Object.entries(params).forEach(([k, v]) => {
      if (v !== undefined && v !== null && v !== '') query[k] = v;
    });
    return (await api.get<NotificationDispatchPage>('/api/ops/notifications/dispatches', { params: query })).data;
  },

  /** GET /api/ops/notifications/dispatches/{id} — 채널별 결과 포함 단건. */
  get: async (id: number): Promise<NotificationDispatchDetail> =>
    (await api.get<NotificationDispatchDetail>(`/api/ops/notifications/dispatches/${id}`)).data,

  /**
   * POST /api/ops/notifications/dispatches/{id}/resend — 원본을 그대로 다시 보낸다.
   *
   * <p>{@code idempotencyKey} 를 <b>반드시</b> 넘긴다. 안 넘기면 더블클릭이 두 번 발송된다.
   */
  resend: async (id: number, idempotencyKey: string): Promise<NotificationResendResult> =>
    (await api.post<NotificationResendResult>(
      `/api/ops/notifications/dispatches/${id}/resend`, { idempotencyKey })).data,
};

export const DISPATCH_STATUS_LABEL: Record<DispatchStatus, string> = {
  PENDING: '미완결',
  DELIVERED: '전건 성공',
  PARTIAL: '일부 성공',
  FAILED: '전건 실패',
  NO_CHANNEL: '채널 없음',
};

export const DISPATCH_STATUS_BADGE: Record<DispatchStatus, string> = {
  PENDING: 'bg-amber-100 text-amber-800',
  DELIVERED: 'bg-green-100 text-green-800',
  PARTIAL: 'bg-sky-100 text-sky-800',
  FAILED: 'bg-red-100 text-red-800',
  NO_CHANNEL: 'bg-gray-200 text-gray-700',
};
