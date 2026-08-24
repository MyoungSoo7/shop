import { describe, it, expect, vi, beforeEach } from 'vitest';
import { couponAdminApi } from '@/api/couponAdmin';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 쿠폰 운영 API 클라이언트 계약.
 *
 * <p>여기서 고정하는 것: 코드가 경로 세그먼트로 들어가므로 <b>인코딩</b>이 필요하다는 점,
 * 그리고 <b>삭제 경로가 없다</b>는 점. 이미 사용된 쿠폰을 지우면 사용 이력의 참조가 끊겨
 * 그 할인이 어디서 왔는지 설명할 수 없다.
 */
describe('couponAdminApi — 조회', () => {
  it('검색은 /admin/coupons 로 간다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await couponAdminApi.search({ code: 'WEL', lifecycle: 'ACTIVE', page: 0, size: 50 });

    expect(mocked.get).toHaveBeenCalledWith('/admin/coupons', {
      params: { code: 'WEL', lifecycle: 'ACTIVE', page: 0, size: 50 },
    });
  });

  it('빈 문자열 필터는 보내지 않는다', async () => {
    mocked.get.mockResolvedValue({ data: { content: [] } } as never);

    await couponAdminApi.search({ code: '', type: 'FIXED' });

    expect(mocked.get).toHaveBeenCalledWith('/admin/coupons', { params: { type: 'FIXED' } });
  });

  it('상태별 장수·enum 목록은 각자의 경로를 쓴다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await couponAdminApi.lifecycleCounts({ code: 'W' });
    expect(mocked.get).toHaveBeenCalledWith('/admin/coupons/lifecycle-counts', {
      params: { code: 'W' },
    });

    await couponAdminApi.enums();
    expect(mocked.get).toHaveBeenCalledWith('/admin/coupons/enums');
  });

  it('사용 내역은 id 로 부르고 limit 기본값은 100 이다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await couponAdminApi.usages(7);
    expect(mocked.get).toHaveBeenCalledWith('/admin/coupons/7/usages', { params: { limit: 100 } });

    await couponAdminApi.usages(7, 500);
    expect(mocked.get).toHaveBeenCalledWith('/admin/coupons/7/usages', { params: { limit: 500 } });
  });
});

describe('couponAdminApi — 중단/재개', () => {
  it('코드로 부른다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await couponAdminApi.deactivate('WELCOME10');
    expect(mocked.post).toHaveBeenCalledWith('/admin/coupons/WELCOME10/deactivate');

    await couponAdminApi.activate('WELCOME10');
    expect(mocked.post).toHaveBeenCalledWith('/admin/coupons/WELCOME10/activate');
  });

  it('코드에 경로 특수문자가 있어도 인코딩해 보낸다 — 그대로 이어 붙이면 경로가 갈라진다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);

    await couponAdminApi.deactivate('SUMMER/2026');

    expect(mocked.post).toHaveBeenCalledWith('/admin/coupons/SUMMER%2F2026/deactivate');
  });

  it('삭제 함수 자체가 존재하지 않는다 — 사용 이력의 참조가 끊기면 할인 출처를 설명할 수 없다', () => {
    expect((couponAdminApi as Record<string, unknown>).delete).toBeUndefined();
    expect((couponAdminApi as Record<string, unknown>).remove).toBeUndefined();
  });
});

describe('couponAdminApi — 내보내기', () => {
  it('blob 으로 받고 잘림·총장수를 헤더에서 읽는다', async () => {
    mocked.get.mockResolvedValue({
      data: new Blob(['x']),
      headers: {
        'content-disposition': 'attachment; filename="coupons_2026-03-02.csv"',
        'x-export-truncated': 'true',
        'x-export-total': '12345',
      },
    } as never);

    const result = await couponAdminApi.export({ lifecycle: 'EXPIRED' });

    expect(mocked.get).toHaveBeenCalledWith('/admin/coupons/export', {
      params: { lifecycle: 'EXPIRED' },
      responseType: 'blob',
    });
    expect(result.fileName).toBe('coupons_2026-03-02.csv');
    expect(result.truncated).toBe(true);
    expect(result.total).toBe(12345);
  });

  it('헤더가 없으면 기본 파일명으로 떨어진다', async () => {
    mocked.get.mockResolvedValue({ data: new Blob(['x']), headers: {} } as never);

    expect((await couponAdminApi.export({})).fileName).toBe('coupons.csv');
  });
});
