import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  newRequestId,
  pointTransferApi,
  type TransferHistoryEntry,
  type TransferResult,
} from '@/api/pointTransfer';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const result: TransferResult = {
  transferNo: 'PT20260828-00000001',
  recipientEmail: 'fr****@example.com',
  recipientName: '김받는',
  amount: 1000,
  remainingBalance: 4000,
  transferredAt: '2026-08-28T10:00:00+09:00',
  alreadyProcessed: false,
};

const entry = (over: Partial<TransferHistoryEntry> = {}): TransferHistoryEntry => ({
  transferNo: 'PT20260828-00000001',
  outgoing: true,
  counterpartName: '김받는',
  amount: 1000,
  message: '고마워',
  transferredAt: '2026-08-28T10:00:00+09:00',
  ...over,
});

describe('pointTransferApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  /**
   * 보내는 사람 칸이 본문에 없는 것이 이 API 의 핵심이다. 주체를 요청이 지정할 수 있으면
   * 남의 포인트를 보내는 요청을 만들 수 있다.
   */
  it('전송 본문에는 보내는 사람 칸이 없다 — 서버가 토큰에서 읽는다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: result });

    await pointTransferApi.send({
      requestId: 'req-1',
      recipientEmail: 'friend@example.com',
      recipientName: '김받는',
      amount: 1000,
      message: '고마워',
    });

    expect(api.post).toHaveBeenCalledWith('/api/points/transfers', {
      requestId: 'req-1',
      recipientEmail: 'friend@example.com',
      recipientName: '김받는',
      amount: 1000,
      message: '고마워',
    });
    const [, body] = vi.mocked(api.post).mock.calls[0];
    expect(body).not.toHaveProperty('senderUserId');
    expect(body).not.toHaveProperty('userId');
  });

  /** 재전송이었다는 사실이 응답에 실려 온다. 숨기면 사용자가 두 번 보냈다고 오해한다. */
  it('이미 처리된 요청이면 alreadyProcessed 가 온다', async () => {
    vi.mocked(api.post).mockResolvedValue({
      data: { ...result, alreadyProcessed: true },
    });

    const sent = await pointTransferApi.send({
      requestId: 'req-1',
      recipientEmail: 'friend@example.com',
      recipientName: '김받는',
      amount: 1000,
    });

    expect(sent.alreadyProcessed).toBe(true);
    expect(sent.transferNo).toBe('PT20260828-00000001');
  });

  /** 이메일은 서버가 가려 준다. 화면이 직접 가리면 서버와 규칙이 갈라진다. */
  it('가려진 이메일을 그대로 받는다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: result });

    const sent = await pointTransferApi.send({
      requestId: 'req-1',
      recipientEmail: 'friend@example.com',
      recipientName: '김받는',
      amount: 1000,
    });

    expect(sent.recipientEmail).toBe('fr****@example.com');
  });

  it('이력은 방향을 담아 온다', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: [entry(), entry({ transferNo: 'PT-2', outgoing: false, message: null })],
    });

    const list = await pointTransferApi.history();

    expect(api.get).toHaveBeenCalledWith('/api/points/transfers', { params: { limit: 20 } });
    expect(list[0].outgoing).toBe(true);
    expect(list[1].outgoing).toBe(false);
    expect(list[1].message).toBeNull();
  });

  it('이력 건수는 넘겨준 값으로 묻는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });

    await pointTransferApi.history(5);

    expect(api.get).toHaveBeenCalledWith('/api/points/transfers', { params: { limit: 5 } });
  });
});

describe('newRequestId', () => {
  it('부를 때마다 다른 값이다 — 같은 값이면 두 번째 전송이 조용히 무시된다', () => {
    const keys = new Set(Array.from({ length: 50 }, () => newRequestId()));

    expect(keys.size).toBe(50);
  });

  /** randomUUID 가 없는 환경(구형 브라우저·비보안 컨텍스트)에서도 화면이 멈추면 안 된다. */
  it('randomUUID 가 없으면 시간+난수로 물러선다', () => {
    const original = globalThis.crypto;
    Object.defineProperty(globalThis, 'crypto', {
      value: {}, configurable: true, writable: true,
    });
    try {
      expect(newRequestId()).toMatch(/^pt-[a-z0-9]+-[a-z0-9]+$/);
    } finally {
      Object.defineProperty(globalThis, 'crypto', {
        value: original, configurable: true, writable: true,
      });
    }
  });
});
