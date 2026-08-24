import { describe, it, expect, vi, beforeEach } from 'vitest';
import { displaySectionApi, type DisplaySection } from '@/api/displaySection';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    patch: vi.fn(),
    delete: vi.fn(),
  },
}));

const mocked = vi.mocked(api);

const section: DisplaySection = {
  id: 7,
  code: 'EXH_2026_SUMMER',
  name: '2026 여름 기획전',
  kind: 'EXHIBITION',
  categoryId: null,
  startsAt: '2026-06-01T00:00:00',
  endsAt: '2026-08-31T23:59:00',
  sortOrder: 3,
  active: true,
};

beforeEach(() => {
  vi.clearAllMocks();
  mocked.get.mockResolvedValue({ data: [] });
  mocked.post.mockResolvedValue({ data: section });
  mocked.patch.mockResolvedValue({ data: section });
  mocked.delete.mockResolvedValue({ data: undefined });
});

describe('displaySectionApi', () => {
  it('운영 목록은 관리 표면에서, 노출 목록은 공개 표면에서 읽는다', async () => {
    mocked.get.mockResolvedValueOnce({ data: [section] });
    expect(await displaySectionApi.listAll()).toEqual([section]);
    expect(mocked.get).toHaveBeenCalledWith('/admin/display-sections');

    await displaySectionApi.listVisible();
    expect(mocked.get).toHaveBeenCalledWith('/display-sections');
  });

  it('편성 내용은 관리 표면에서 읽는다 — 공개 표면은 노출 중인 것만 다룬다', async () => {
    await displaySectionApi.items('EXH_2026_SUMMER');
    expect(mocked.get).toHaveBeenCalledWith('/admin/display-sections/EXH_2026_SUMMER/items');
  });

  it('편성 코드는 URL 조각으로 인코딩한다', async () => {
    await displaySectionApi.items('EXH A/B');
    expect(mocked.get).toHaveBeenCalledWith('/admin/display-sections/EXH%20A%2FB/items');
  });

  it('생성은 입력을 그대로 싣는다', async () => {
    await displaySectionApi.create({
      code: 'EXH_2026_SUMMER',
      name: '2026 여름 기획전',
      kind: 'EXHIBITION',
      categoryId: null,
      startsAt: '2026-06-01T00:00',
      endsAt: '2026-08-31T23:59',
      sortOrder: 3,
    });

    expect(mocked.post).toHaveBeenCalledWith('/admin/display-sections', {
      code: 'EXH_2026_SUMMER',
      name: '2026 여름 기획전',
      kind: 'EXHIBITION',
      categoryId: null,
      startsAt: '2026-06-01T00:00',
      endsAt: '2026-08-31T23:59',
      sortOrder: 3,
    });
  });

  it('상품 추가·제거·기간 변경·활성 전환이 각자의 표면으로 간다', async () => {
    await displaySectionApi.addItem('EXH_2026_SUMMER', { productId: 101, sortOrder: 2, pinned: true });
    expect(mocked.post).toHaveBeenCalledWith('/admin/display-sections/EXH_2026_SUMMER/items',
      { productId: 101, sortOrder: 2, pinned: true });

    await displaySectionApi.removeItem('EXH_2026_SUMMER', 101);
    expect(mocked.delete).toHaveBeenCalledWith('/admin/display-sections/EXH_2026_SUMMER/items/101');

    await displaySectionApi.reschedule('EXH_2026_SUMMER', { startsAt: null, endsAt: '2026-09-30T23:59' });
    expect(mocked.patch).toHaveBeenCalledWith('/admin/display-sections/EXH_2026_SUMMER/schedule',
      { startsAt: null, endsAt: '2026-09-30T23:59' });

    await displaySectionApi.setActive('EXH_2026_SUMMER', false);
    expect(mocked.patch).toHaveBeenCalledWith('/admin/display-sections/EXH_2026_SUMMER/active', null,
      { params: { active: false } });
  });
});
