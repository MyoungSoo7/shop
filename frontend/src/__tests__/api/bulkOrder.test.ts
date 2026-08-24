import { describe, it, expect, vi, beforeEach } from 'vitest';
import { bulkOrderApi } from '@/api/bulkOrder';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), delete: vi.fn() },
}));

const mocked = vi.mocked(api);

beforeEach(() => vi.clearAllMocks());

/**
 * 대량주문 API 클라이언트 계약.
 *
 * <p>여기서 고정하는 것: 업로드와 확정이 <b>다른 호출</b>이라는 점, 그리고 업로드가
 * Content-Type 을 비워 보낸다는 점. 기본값(application/json)이 남으면 multipart boundary 가
 * 실리지 않아 서버가 파트를 하나도 못 읽는데, 증상은 "파일이 비었다"로 위장한다.
 */
describe('bulkOrderApi — 읽기', () => {
  it('열 정의·목록·단건은 각자의 경로를 쓴다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await bulkOrderApi.columns();
    expect(mocked.get).toHaveBeenCalledWith('/api/bulk-orders/columns');

    await bulkOrderApi.list();
    expect(mocked.get).toHaveBeenCalledWith('/api/bulk-orders');

    await bulkOrderApi.get(7);
    expect(mocked.get).toHaveBeenCalledWith('/api/bulk-orders/7');
  });

  it('응답 본문(data)만 돌려준다 — 호출부가 axios 응답 껍데기를 알 필요가 없다', async () => {
    mocked.get.mockResolvedValue({ data: [{ columnIndex: 0 }], status: 200 } as never);

    expect(await bulkOrderApi.columns()).toEqual([{ columnIndex: 0 }]);
  });
});

describe('bulkOrderApi — 업로드', () => {
  it('파일을 FormData 로 싣고 Content-Type 을 지운다 — 남으면 boundary 가 안 실린다', async () => {
    mocked.post.mockResolvedValue({ data: { id: 1 } } as never);

    await bulkOrderApi.upload(new File(['a,b'], 'orders.csv', { type: 'text/csv' }));

    const [path, body, config] = mocked.post.mock.calls[0];
    expect(path).toBe('/api/bulk-orders');
    expect(body).toBeInstanceOf(FormData);
    expect((body as FormData).get('file')).toBeInstanceOf(File);
    expect((config as { headers: Record<string, unknown> }).headers['Content-Type']).toBeUndefined();
  });
});

describe('bulkOrderApi — 검증과 확정은 갈라져 있다', () => {
  it('재검증·확정·폐기는 각자의 경로를 쓴다 — 올리는 순간 주문이 나가지 않는다', async () => {
    mocked.post.mockResolvedValue({ data: {} } as never);
    mocked.delete.mockResolvedValue({ data: undefined } as never);

    await bulkOrderApi.revalidate(3);
    expect(mocked.post).toHaveBeenCalledWith('/api/bulk-orders/3/revalidate');

    await bulkOrderApi.confirm(3);
    expect(mocked.post).toHaveBeenCalledWith('/api/bulk-orders/3/confirm');

    await bulkOrderApi.discard(3);
    expect(mocked.delete).toHaveBeenCalledWith('/api/bulk-orders/3');
  });

  it('업로드 경로 자체가 확정을 겸하지 않는다 — POST /api/bulk-orders 는 초안만 만든다', async () => {
    mocked.post.mockResolvedValue({ data: { status: 'UPLOADED' } } as never);

    const draft = await bulkOrderApi.upload(new File(['x'], 'a.csv'));

    expect(draft.status).toBe('UPLOADED');
    expect(mocked.post.mock.calls.every(([p]) => !String(p).endsWith('/confirm'))).toBe(true);
  });

  it('폐기는 아무것도 돌려주지 않는다', async () => {
    mocked.delete.mockResolvedValue({ data: undefined } as never);

    await expect(bulkOrderApi.discard(9)).resolves.toBeUndefined();
  });
});
