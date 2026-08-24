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
