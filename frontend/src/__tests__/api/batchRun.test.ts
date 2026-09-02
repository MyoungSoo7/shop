import { describe, it, expect, vi, beforeEach } from 'vitest';
import { batchRunApi } from '@/api/batchRun';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 배치 실행 원장 API 클라이언트 계약.
 *
 * <p>여기서 못박는 것 —
 *
 * <ul>
 *   <li><b>세 조회가 서로 다른 경로다.</b> 목록·배치별 최근·재실행 가능 목록은 답이 다른 질문이라
 *       한 경로로 합칠 수 없다. 하나로 합치면 "마지막 성공이 언제인가" 를 목록에서 눈으로 찾아야 한다.
 *   <li><b>배치명은 URL 에 인코딩해 넣는다.</b> 이름에 슬래시가 섞이면 다른 엔드포인트로 새고,
 *       그 요청은 404 가 아니라 <i>다른 배치</i>를 돌릴 수도 있다.
 *   <li><b>targetDate 는 본문에 반드시 실린다.</b> 서버가 {@code @NotNull} 로 막는 값이라
 *       빠지면 400 이고, 재실행이 "어느 날짜분인지 모르는 채" 도는 것을 그 제약이 막는다.
 * </ul>
 */
describe('batchRunApi — 경로', () => {
  it('세 조회가 각자 다른 경로로 간다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await batchRunApi.search({ size: 50 });
    expect(mocked.get).toHaveBeenCalledWith('/admin/batch-runs', { params: { size: 50 } });

    await batchRunApi.latest();
    expect(mocked.get).toHaveBeenCalledWith('/admin/batch-runs/latest');

    await batchRunApi.rerunnable();
    expect(mocked.get).toHaveBeenCalledWith('/admin/batch-runs/rerunnable');
  });

  it('검색 파라미터는 손대지 않고 그대로 넘긴다 — 서버가 상한·정렬의 주인이다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [], number: 0, size: 50, totalElements: 0, totalPages: 0 } } as never);

    await batchRunApi.search({ batchName: 'point-lot-expiry', status: 'FAILED', targetDate: '2026-09-01' });

    expect(mocked.get).toHaveBeenCalledWith('/admin/batch-runs', {
      params: { batchName: 'point-lot-expiry', status: 'FAILED', targetDate: '2026-09-01' },
    });
  });

  it('응답은 data 만 꺼내 돌려준다', async () => {
    const page = { content: [], number: 0, size: 50, totalElements: 3, totalPages: 1 };
    mocked.get.mockResolvedValue({ data: page } as never);

    await expect(batchRunApi.search({})).resolves.toBe(page);
  });
});

describe('batchRunApi — 재실행', () => {
  it('배치명을 URL 인코딩한다 — 슬래시가 섞이면 다른 엔드포인트로 샌다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await batchRunApi.rerun('a/b c', '2026-09-01', true);

    expect(mocked.post).toHaveBeenCalledWith(
      '/admin/batch-runs/a%2Fb%20c/rerun',
      { targetDate: '2026-09-01', dryRun: true },
    );
  });

  it('targetDate 와 dryRun 은 본문에 실린다 — 쿼리스트링이 아니다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await batchRunApi.rerun('payment-expiry', '2026-08-30', false);

    expect(mocked.post).toHaveBeenCalledWith(
      '/admin/batch-runs/payment-expiry/rerun',
      { targetDate: '2026-08-30', dryRun: false },
    );
  });

  it('실행 결과를 그대로 돌려준다 — dryRun 여부는 서버가 판정한 값이다', async () => {
    const result = { batchName: 'payment-expiry', targetDate: '2026-08-30', dryRun: true, processedCount: 12 };
    mocked.post.mockResolvedValue({ data: result } as never);

    await expect(batchRunApi.rerun('payment-expiry', '2026-08-30', true)).resolves.toEqual(result);
  });
});
