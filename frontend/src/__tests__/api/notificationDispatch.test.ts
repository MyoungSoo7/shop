import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  notificationDispatchApi,
  DISPATCH_STATUS_LABEL,
  DISPATCH_STATUS_BADGE,
  type DispatchStatus,
  type NotificationDispatch,
  type NotificationDispatchDetail,
} from '@/api/operation';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const row: NotificationDispatch = {
  id: 12,
  eventId: 'evt-9',
  type: 'SETTLEMENT_CONFIRMED',
  recipient: 'ops@lemuel.co.kr',
  subject: '정산 확정',
  status: 'PARTIAL',
  channelsTotal: 2,
  channelsSucceeded: 1,
  resentFromId: null,
  createdAt: '2026-08-25T01:00:00Z',
  completedAt: '2026-08-25T01:00:01Z',
};

const detail: NotificationDispatchDetail = {
  ...row,
  body: '본문',
  channels: [
    { channel: 'log', status: 'SUCCESS', attempts: 1, error: null, createdAt: '2026-08-25T01:00:01Z' },
    { channel: 'slack', status: 'FAILURE', attempts: 3, error: 'timeout after 3000ms', createdAt: null },
  ],
};

describe('notificationDispatchApi', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset();
    vi.mocked(api.post).mockReset();
  });

  it('빈 필터는 쿼리에서 빠진다 — 빈 문자열을 보내면 서버가 정확일치로 아무것도 못 찾는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { items: [row], total: 1, limit: 20, offset: 0 } });

    await notificationDispatchApi.search({ status: undefined, recipient: '', limit: 20, offset: 0 });

    expect(api.get).toHaveBeenCalledWith('/api/ops/notifications/dispatches', {
      params: { limit: 20, offset: 0 },
    });
  });

  it('상태·수신자 필터를 그대로 넘긴다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: { items: [], total: 0, limit: 20, offset: 0 } });

    await notificationDispatchApi.search({ status: 'FAILED', recipient: 'a@b.c' });

    expect(api.get).toHaveBeenCalledWith('/api/ops/notifications/dispatches', {
      params: { status: 'FAILED', recipient: 'a@b.c' },
    });
  });

  it('단건 상세는 채널별 결과를 그대로 돌려준다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: detail });

    const result = await notificationDispatchApi.get(12);

    expect(api.get).toHaveBeenCalledWith('/api/ops/notifications/dispatches/12');
    expect(result.channels).toHaveLength(2);
    expect(result.channels[1].error).toBe('timeout after 3000ms');
  });

  it('재발송은 멱등 키를 반드시 실어 보낸다 — 없으면 더블클릭이 두 번 나간다', async () => {
    vi.mocked(api.post).mockResolvedValue({
      data: { originalId: 12, eventId: 'resend:12:k', deduped: false, allSucceeded: true, results: [] },
    });

    const result = await notificationDispatchApi.resend(12, 'k');

    expect(api.post).toHaveBeenCalledWith('/api/ops/notifications/dispatches/12/resend',
      { idempotencyKey: 'k' });
    expect(result.eventId).toBe('resend:12:k');
  });

  it('상태 5종 모두 라벨과 배지가 있다 — 하나라도 빠지면 화면에 undefined 가 뜬다', () => {
    const all: DispatchStatus[] = ['PENDING', 'DELIVERED', 'PARTIAL', 'FAILED', 'NO_CHANNEL'];
    all.forEach((s) => {
      expect(DISPATCH_STATUS_LABEL[s]).toBeTruthy();
      expect(DISPATCH_STATUS_BADGE[s]).toBeTruthy();
    });
  });
});
