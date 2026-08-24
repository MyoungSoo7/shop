import { describe, it, expect, vi, beforeEach } from 'vitest';
import { pgRoutingApi, type PgHealth } from '@/api/pgRouting';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

describe('pgRoutingApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('상태 스냅샷을 관리 엔드포인트에서 읽는다', async () => {
    const healthy: PgHealth = {
      providers: { TOSS: true, KCP: true, NICE: true, INICIS: true, MOCK: true },
      healthy: true,
    };
    vi.mocked(api.get).mockResolvedValueOnce({ data: healthy });

    const result = await pgRoutingApi.health();

    expect(api.get).toHaveBeenCalledWith('/admin/pg/health');
    expect(result.healthy).toBe(true);
  });

  it('PG 목록을 화면이 짓지 않고 서버 응답을 그대로 받는다', async () => {
    // 서버 enum 이 정본이다. 클라이언트가 목록을 하드코딩하면 PG 를 추가한 날
    // 화면에서만 조용히 사라진다.
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { providers: { TOSS: true, NEWPG: false }, healthy: false } satisfies PgHealth,
    });

    const result = await pgRoutingApi.health();

    expect(Object.keys(result.providers)).toEqual(['TOSS', 'NEWPG']);
  });

  it('하나라도 차단되면 healthy 는 false 로 내려온다', async () => {
    // CircuitBreaker 가 OPEN 인 PG 는 false 로 오고, 라우터는 그 PG 를 후보에서 뺀다.
    vi.mocked(api.get).mockResolvedValueOnce({
      data: { providers: { TOSS: false, KCP: true }, healthy: false } satisfies PgHealth,
    });

    const result = await pgRoutingApi.health();

    expect(result.healthy).toBe(false);
    expect(result.providers.TOSS).toBe(false);
  });

  it('실패는 삼키지 않고 호출자에게 전파한다', async () => {
    // 장애 중에 보는 화면이다 — 조회가 실패했는데 "정상"으로 그리면 최악이다.
    vi.mocked(api.get).mockRejectedValueOnce(new Error('503 Service Unavailable'));

    await expect(pgRoutingApi.health()).rejects.toThrow('503');
  });
});
