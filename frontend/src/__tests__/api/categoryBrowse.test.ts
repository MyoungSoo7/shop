import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  categoryBrowseApi,
  categoryTrail,
  flattenCategories,
  type BrowseCategory,
} from '@/api/categoryBrowse';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

const node = (over: Partial<BrowseCategory> = {}): BrowseCategory => ({
  id: 1,
  name: '전자',
  slug: 'electronics',
  parentId: null,
  depth: 0,
  sortOrder: 1,
  isActive: true,
  createdAt: '2026-08-01T00:00:00+09:00',
  updatedAt: '2026-08-01T00:00:00+09:00',
  children: [],
  ...over,
});

describe('categoryBrowseApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  /**
   * 관리 API 를 부르면 아직 열지 않은 분류가 구매자에게 그대로 나간다. 두 API 는 이름만
   * 비슷할 뿐 나가는 내용이 다르다.
   */
  it('공개 트리는 /categories 를 부른다 — 관리 API(/admin/categories)가 아니다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [node()] });

    await categoryBrowseApi.tree();

    expect(api.get).toHaveBeenCalledWith('/categories');
    const [path] = vi.mocked(api.get).mock.calls[0];
    expect(path).not.toContain('/admin');
  });

  it('슬러그 한 건은 경로에 인코딩해 넣는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: node({ slug: '가전 제품' }) });

    await categoryBrowseApi.bySlug('가전 제품');

    expect(api.get).toHaveBeenCalledWith(`/categories/${encodeURIComponent('가전 제품')}`);
  });

  /** 전체를 받아 화면에서 거르면 상품이 늘수록 안 쓰는 데이터만 커진다. */
  it('상품은 categoryId 를 서버에 넘겨 거른다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });

    await categoryBrowseApi.products(42);

    expect(api.get).toHaveBeenCalledWith('/api/products?categoryId=42');
  });
});

describe('flattenCategories', () => {
  it('깊이 우선으로 편다 — 순서가 곧 화면 순서다', () => {
    const tree = [
      node({ id: 1, name: '전자', children: [node({ id: 2, name: '노트북' })] }),
      node({ id: 3, name: '의류' }),
    ];

    expect(flattenCategories(tree).map((c) => c.name)).toEqual(['전자', '노트북', '의류']);
  });

  it('children 이 비어도 터지지 않는다', () => {
    expect(flattenCategories([])).toEqual([]);
  });
});

describe('categoryTrail', () => {
  const tree = [
    node({
      id: 1,
      name: '전자',
      children: [node({ id: 2, name: '노트북', children: [node({ id: 3, name: '게이밍' })] })],
    }),
    node({ id: 4, name: '의류' }),
  ];

  /**
   * 서버가 주는 depth 만으로는 "무엇 밑의 무엇"인지 못 그린다 — 같은 depth 의 형제가 여럿이라서다.
   */
  it('최상위부터 그 노드까지의 경로를 준다', () => {
    expect(categoryTrail(tree, 3).map((c) => c.name)).toEqual(['전자', '노트북', '게이밍']);
  });

  it('최상위 자신은 한 칸짜리 경로다', () => {
    expect(categoryTrail(tree, 4).map((c) => c.name)).toEqual(['의류']);
  });

  it('없는 id 는 빈 배열이다 — 못 찾은 것과 최상위를 섞지 않는다', () => {
    expect(categoryTrail(tree, 999)).toEqual([]);
  });
});
