import { describe, it, expect, vi, beforeEach } from 'vitest';
import { pointApi } from '@/api/point';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({ default: { get: vi.fn(), post: vi.fn() } }));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 포인트 원장 API 클라이언트 계약.
 *
 * <p>여기서 고정하는 두 가지. ① <b>만료 실행의 기본값은 미리보기</b> — 호출부가 dryRun 을
 * 빠뜨렸을 때 실제로 소멸하는 기본값이면 실수 한 번이 고객 재산을 지운다. ② 지급·차감은
 * <b>referenceId 를 그대로 실어 보낸다</b> — 클라이언트가 매번 새 키를 지어내면 멱등이 무너져
 * 이중 클릭이 이중 지급이 된다.
 */
describe('pointApi — 지급·차감', () => {
  it('지급은 멱등 키를 그대로 실어 보낸다', async () => {
    mocked.post.mockResolvedValue({ data: { entryId: 1 } } as never);
    const body = { userId: 3, amount: 1000, referenceId: 'CS-2026-0001', reason: '보상' };

    await pointApi.grant(body);

    expect(mocked.post).toHaveBeenCalledWith('/admin/points/grants', body);
  });

  it('차감은 지급과 다른 경로다 — 음수 지급으로 눙치지 않는다', async () => {
    mocked.post.mockResolvedValue({ data: { entryId: 2 } } as never);
    const body = { userId: 3, amount: 500, referenceId: 'CS-2026-0002', reason: '오지급 회수' };

    await pointApi.deduct(body);

    expect(mocked.post).toHaveBeenCalledWith('/admin/points/deductions', body);
  });
});

describe('pointApi — 만료 실행', () => {
  it('기본값은 미리보기다 — 인자를 빠뜨려도 소멸하지 않는다', async () => {
    mocked.post.mockResolvedValue({ data: { dryRun: true } } as never);

    await pointApi.runExpiry();

    expect(mocked.post).toHaveBeenCalledWith('/admin/points/expiry/run', null, {
      params: { dryRun: true, batchSize: 500 },
    });
  });

  it('실제 소멸은 명시적으로 dryRun=false 여야 한다', async () => {
    mocked.post.mockResolvedValue({ data: { dryRun: false } } as never);

    await pointApi.runExpiry(false, 50);

    expect(mocked.post).toHaveBeenCalledWith('/admin/points/expiry/run', null, {
      params: { dryRun: false, batchSize: 50 },
    });
  });
});

describe('pointApi — 조회', () => {
  it('내 잔액은 식별자 없이 부른다', async () => {
    mocked.get.mockResolvedValue({ data: { available: 100 } } as never);

    await pointApi.myBalance();

    expect(mocked.get).toHaveBeenCalledWith('/api/points/me');
  });

  it('요약·만료예정은 기본 기간을 채워 보낸다', async () => {
    mocked.get.mockResolvedValue({ data: {} } as never);

    await pointApi.summary();
    expect(mocked.get).toHaveBeenCalledWith('/admin/points/summary', {
      params: { withinDays: 30 },
    });

    await pointApi.expiring();
    expect(mocked.get).toHaveBeenCalledWith('/admin/points/expiring', {
      params: { withinDays: 30, limit: 50 },
    });

    await pointApi.expiring(7, 10);
    expect(mocked.get).toHaveBeenCalledWith('/admin/points/expiring', {
      params: { withinDays: 7, limit: 10 },
    });
  });

  it('계정 상세는 사용자 식별자를 경로에 싣는다', async () => {
    mocked.get.mockResolvedValue({ data: {} } as never);

    await pointApi.account(42);

    expect(mocked.get).toHaveBeenCalledWith('/admin/points/accounts/42');
  });
});

describe('pointApi — 적립 정책', () => {
  it('목록·등록은 같은 경로를 메서드로 가른다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);
    mocked.post.mockResolvedValue({ data: {} } as never);
    const body = {
      scope: 'GLOBAL' as const, scopeKey: '-', earnRate: 0.01, validityDays: 365,
      effectiveFrom: '2026-09-01', reason: '기본 적립',
    };

    await pointApi.policies();
    expect(mocked.get).toHaveBeenCalledWith('/admin/points/policies');

    await pointApi.registerPolicy(body);
    expect(mocked.post).toHaveBeenCalledWith('/admin/points/policies', body);
  });

  it('종료는 종료일을 본문으로 보낸다 — 반열림이라 그날부터는 적용하지 않는다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await pointApi.closePolicy(5, '2026-10-01');

    expect(mocked.post).toHaveBeenCalledWith('/admin/points/policies/5/close', {
      effectiveTo: '2026-10-01',
    });
  });
});
