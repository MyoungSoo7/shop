import { describe, it, expect, vi, beforeEach } from 'vitest';
import { categoryIntegrityApi, type CategoryCountIntegrity } from '@/api/categoryIntegrity';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const mocked = vi.mocked(api);

const report: CategoryCountIntegrity = {
  drifted: 2,
  healthy: false,
  byKind: { OVERCOUNT: 1, UNDERCOUNT: 1 },
  samples: [
    { categoryId: 1, slug: 'shoes', name: '신발', cachedCount: 12, actualCount: 9, difference: 3, kind: 'OVERCOUNT' },
  ],
  unreadable: 0,
};

beforeEach(() => {
  vi.clearAllMocks();
  mocked.get.mockResolvedValue({ data: report });
  mocked.post.mockResolvedValue({ data: 2 });
});

describe('categoryIntegrityApi', () => {
  it('점검은 표본 상한을 실어 조회한다', async () => {
    expect(await categoryIntegrityApi.checkCounts(20)).toEqual(report);
    expect(mocked.get).toHaveBeenCalledWith('/admin/categories/count-integrity',
      { params: { sampleLimit: 20 } });
  });

  it('상한을 안 주면 서버 기본값에 맡긴다', async () => {
    await categoryIntegrityApi.checkCounts();
    expect(mocked.get).toHaveBeenCalledWith('/admin/categories/count-integrity', { params: {} });
  });

  it('재계산은 갱신된 행 수를 준다 — 점검과 다른 표면이다(고치는 것은 사람의 결정)', async () => {
    expect(await categoryIntegrityApi.refreshCounts()).toBe(2);
    expect(mocked.post).toHaveBeenCalledWith('/admin/categories/refresh-counts');
  });
});
