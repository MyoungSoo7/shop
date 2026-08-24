import { describe, it, expect, vi, beforeEach } from 'vitest';
import { reviewAdminApi } from '@/api/reviewAdmin';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 리뷰 관리 API 클라이언트 계약.
 *
 * <p>여기서 고정하는 것은 <b>삭제 경로가 없다</b>는 사실과, 블라인드가 사유를 반드시 실어
 * 보낸다는 점이다. 원문을 지우면 작성자 이의 제기에 답할 근거가 사라진다.
 */
describe('reviewAdminApi — 조회', () => {
  it('검색은 /admin/reviews 로 간다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await reviewAdminApi.search({ keyword: '최악', maxRating: 2, page: 0, size: 50 });

    expect(mocked.get).toHaveBeenCalledWith('/admin/reviews', {
      params: { keyword: '최악', maxRating: 2, page: 0, size: 50 },
    });
  });

  it('빈 문자열 필터는 보내지 않는다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await reviewAdminApi.search({ keyword: '', status: 'HIDDEN' });

    expect(mocked.get).toHaveBeenCalledWith('/admin/reviews', { params: { status: 'HIDDEN' } });
  });

  it('상태별 건수·상태 목록은 각자의 경로를 쓴다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await reviewAdminApi.statusCounts({ maxRating: 2 });
    expect(mocked.get).toHaveBeenCalledWith('/admin/reviews/status-counts', {
      params: { maxRating: 2 },
    });

    await reviewAdminApi.statuses();
    expect(mocked.get).toHaveBeenCalledWith('/admin/reviews/statuses');
  });
});

describe('reviewAdminApi — 블라인드', () => {
  it('숨기기는 사유를 함께 보낸다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await reviewAdminApi.hide(11, '욕설 신고');

    expect(mocked.post).toHaveBeenCalledWith('/admin/reviews/11/hide', { reason: '욕설 신고' });
  });

  it('해제는 본문 없이 보낸다 — 되돌리는 데 사유를 요구하면 오판 정정이 늦어진다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await reviewAdminApi.restore(11);

    expect(mocked.post).toHaveBeenCalledWith('/admin/reviews/11/restore');
  });

  it('삭제 함수 자체가 존재하지 않는다 — 원문이 사라지면 이의 제기에 답할 수 없다', () => {
    expect((reviewAdminApi as Record<string, unknown>).delete).toBeUndefined();
    expect((reviewAdminApi as Record<string, unknown>).remove).toBeUndefined();
  });
});

describe('reviewAdminApi — 내보내기', () => {
  it('blob 으로 받고 잘림·총건수를 헤더에서 읽는다', async () => {
    mocked.get.mockResolvedValue({
      data: new Blob(['x']),
      headers: {
        'content-disposition': 'attachment; filename="reviews_2026-03-02.csv"',
        'x-export-truncated': 'false',
        'x-export-total': '42',
      },
    } as never);

    const result = await reviewAdminApi.export({ status: 'HIDDEN' });

    expect(mocked.get).toHaveBeenCalledWith('/admin/reviews/export', {
      params: { status: 'HIDDEN' },
      responseType: 'blob',
    });
    expect(result.fileName).toBe('reviews_2026-03-02.csv');
    expect(result.truncated).toBe(false);
    expect(result.total).toBe(42);
  });

  it('헤더가 없으면 기본 파일명으로 떨어진다', async () => {
    mocked.get.mockResolvedValue({ data: new Blob(['x']), headers: {} } as never);

    expect((await reviewAdminApi.export({})).fileName).toBe('reviews.csv');
  });
});
