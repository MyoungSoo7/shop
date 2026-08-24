import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  operationApi,
  STATUS_LABEL,
  STATUS_BADGE,
  SEVERITY_LABEL,
  SEVERITY_BADGE,
  CATEGORY_LABEL,
  type Incident,
  type IncidentDetail,
} from '@/api/operation';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const incident: Incident = {
  id: 7,
  correlationKey: 'kafka-lag-settlement',
  source: 'ALERTMANAGER',
  category: 'KAFKA_BACKLOG',
  severity: 'CRITICAL',
  status: 'OPEN',
  title: 'settlement 컨슈머 lag 급증',
  service: 'settlement-service',
  firstSeenAt: '2026-08-14T01:00:00Z',
  lastSeenAt: '2026-08-14T01:10:00Z',
  occurrenceCount: 4,
  acknowledgedBy: null,
  acknowledgedAt: null,
  resolvedBy: null,
  resolvedAt: null,
};

const detail: IncidentDetail = {
  incident,
  description: '컨슈머 그룹 지연 3만건',
  labels: { alertname: 'KafkaLag' },
  annotations: { runbook: 'docs/runbook' },
  timeline: [{ eventType: 'OPENED', actor: 'alertmanager', note: null, createdAt: '2026-08-14T01:00:00Z' }],
};

describe('operationApi.search', () => {
  beforeEach(() => vi.resetAllMocks());

  it('빈 파라미터면 빈 쿼리로 목록을 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { content: [incident], page: 0, size: 20, totalElements: 1, totalPages: 1 },
    });

    const result = await operationApi.search();

    expect(api.get).toHaveBeenCalledWith('/api/ops/incidents', { params: {} });
    expect(result.content[0].id).toBe(7);
  });

  it('undefined·null·빈 문자열 필터는 쿼리에서 제거한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
    });

    await operationApi.search({
      status: 'OPEN',
      category: undefined,
      severity: '' as never,
      from: '2026-08-01T00:00:00Z',
      to: undefined,
      page: 0,
      size: 50,
    });

    expect(api.get).toHaveBeenCalledWith('/api/ops/incidents', {
      params: { status: 'OPEN', from: '2026-08-01T00:00:00Z', page: 0, size: 50 },
    });
  });
});

describe('operationApi 단건·요약', () => {
  beforeEach(() => vi.resetAllMocks());

  it('단건 상세는 타임라인을 포함한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: detail });

    const result = await operationApi.get(7);

    expect(api.get).toHaveBeenCalledWith('/api/ops/incidents/7');
    expect(result.timeline).toHaveLength(1);
  });

  it('요약은 기본 24h 윈도로 조회한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { window: '24h', openTotal: 3, byStatus: {}, byCategory: {}, bySeverity: {}, mttrSeconds: 600 },
    });

    const result = await operationApi.summary();

    expect(api.get).toHaveBeenCalledWith('/api/ops/incidents/summary', { params: { window: '24h' } });
    expect(result.openTotal).toBe(3);
  });

  it('요약 윈도를 지정할 수 있다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { window: '7d', openTotal: 9, byStatus: {}, byCategory: {}, bySeverity: {}, mttrSeconds: null },
    });

    await operationApi.summary('7d');

    expect(api.get).toHaveBeenCalledWith('/api/ops/incidents/summary', { params: { window: '7d' } });
  });
});

describe('operationApi 상태 전이', () => {
  beforeEach(() => vi.resetAllMocks());

  it('확인 처리 — note 미지정 시 null 로 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: detail });

    await operationApi.acknowledge(7);

    expect(api.post).toHaveBeenCalledWith('/api/ops/incidents/7/ack', { note: null });
  });

  it('확인 처리 — note 를 실어 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: detail });

    await operationApi.acknowledge(7, '담당자 확인');

    expect(api.post).toHaveBeenCalledWith('/api/ops/incidents/7/ack', { note: '담당자 확인' });
  });

  it('수동 해제', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: detail });

    await operationApi.resolve(7, '복구 완료');

    expect(api.post).toHaveBeenCalledWith('/api/ops/incidents/7/resolve', { note: '복구 완료' });
  });

  it('수동 해제 — note 미지정', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: detail });

    await operationApi.resolve(7);

    expect(api.post).toHaveBeenCalledWith('/api/ops/incidents/7/resolve', { note: null });
  });

  it('오탐 처리', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: detail });

    await operationApi.markFalsePositive(7, '알람 임계 오설정');

    expect(api.post).toHaveBeenCalledWith('/api/ops/incidents/7/false-positive', {
      note: '알람 임계 오설정',
    });
  });

  it('오탐 처리 — note 미지정', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: detail });

    await operationApi.markFalsePositive(7);

    expect(api.post).toHaveBeenCalledWith('/api/ops/incidents/7/false-positive', { note: null });
  });

  it('코멘트 추가', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: detail });

    await operationApi.comment(7, '재발 감시 중');

    expect(api.post).toHaveBeenCalledWith('/api/ops/incidents/7/comments', { note: '재발 감시 중' });
  });
});

describe('표시용 메타', () => {
  it('상태·심각도·카테고리 라벨이 모든 코드값을 덮는다', () => {
    expect(STATUS_LABEL.OPEN).toBe('열림');
    expect(STATUS_LABEL.FALSE_POSITIVE).toBe('오탐');
    expect(SEVERITY_LABEL.CRITICAL).toBe('심각');
    expect(CATEGORY_LABEL.KAFKA_BACKLOG).toBe('Kafka 적체');
    expect(CATEGORY_LABEL.UNKNOWN).toBe('미분류');
    expect(Object.keys(STATUS_LABEL)).toEqual(Object.keys(STATUS_BADGE));
    expect(Object.keys(SEVERITY_LABEL)).toEqual(Object.keys(SEVERITY_BADGE));
    expect(Object.keys(CATEGORY_LABEL)).toHaveLength(11);
  });
});
