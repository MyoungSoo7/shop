import api from './axios';
import type { ProductResponse } from '@/types';

/**
 * 공개 카테고리 탐색 — order-service {@code PublicEcommerceCategoryController}.
 *
 * <p><b>왜 생겼나.</b> 카테고리 트리는 오래전부터 있었지만 그것을 <b>구매자가</b> 보는 길이
 * 없었다. 있던 화면은 {@code /admin/system/ecommerce-categories} 하나 —
 * 만들고·옮기고·지우는 <b>운영자용</b>이고, 부르는 API 도 {@code /admin/categories} 다.
 * 그래서 구매자에게 "노트북은 어디 있나"를 묻는 경로는 검색창뿐이었다: 이름을 정확히
 * 떠올려야만 찾을 수 있고, 무엇이 있는지 <b>둘러보는</b> 길은 없었다.
 *
 * <h3>관리 API 와 다른 API 다</h3>
 * <p>경로가 {@code /admin} 없이 {@code /categories} 인 것은 축약이 아니다. 이쪽은
 * {@code permitAll} 이고 <b>활성 카테고리만</b> 트리로 내려온다. 관리 API 는 비활성까지 전부
 * 내려주므로, 구매자 화면이 관리 API 를 부르면 아직 열지 않은 분류가 그대로 노출된다.
 *
 * <h3>slug 로도 한 건을 집는 이유</h3>
 * <p>트리를 받아 그 안에서 찾으면 될 것 같지만, 링크로 바로 들어온 경우({@code ?category=슬러그})
 * 그 슬러그가 <b>지금도 살아 있는지</b>를 판정할 주체가 필요하다. 트리에서 못 찾은 것과
 * 서버가 없다고 한 것은 다르다 — 앞의 것은 화면의 탐색 실패고 뒤의 것은 사실이다.
 * 이 조회는 자식을 <b>비워서</b> 준다(서버의 {@code fromWithoutChildren}). 하위 분류가 필요하면
 * 트리 쪽을 본다.
 */

/** 카테고리 한 마디. 트리 조회에서는 {@link children} 이 채워져 오고, slug 조회에서는 비어 온다. */
export interface BrowseCategory {
  id: number;
  name: string;
  slug: string;
  parentId: number | null;
  depth: number;
  sortOrder: number;
  isActive: boolean;
  createdAt: string;
  updatedAt: string;
  children: BrowseCategory[];
}

// 경로는 전체 리터럴로 적는다. 조각을 이어 붙이면 사람 눈에도, 저장소의 화면-API 대조
// 게이트(api-screen-gate)에도 어떤 엔드포인트를 부르는지 보이지 않는다.
export const categoryBrowseApi = {
  /** GET — 활성 카테고리 트리(최상위부터, 자식 포함). */
  tree: async (): Promise<BrowseCategory[]> =>
    (await api.get<BrowseCategory[]>('/categories')).data,

  /** GET — 슬러그 한 건. 자식은 비어 온다. 없으면 404 가 그대로 던져진다. */
  bySlug: async (slug: string): Promise<BrowseCategory> =>
    (await api.get<BrowseCategory>(`/categories/${encodeURIComponent(slug)}`)).data,

  /**
   * GET — 그 분류에 속한 상품.
   *
   * <p>{@code /api/products} 는 {@code categoryId} 가 있으면 검색 분기를 타므로, 전체 목록을
   * 받아 화면에서 거르지 않는다 — 거르는 쪽이면 상품이 늘수록 안 쓰는 데이터만 커진다.
   */
  products: async (categoryId: number): Promise<ProductResponse[]> =>
    (await api.get<ProductResponse[]>(`/api/products?categoryId=${categoryId}`)).data,
};

/** 트리를 깊이 우선으로 편다 — 화면이 들여쓰기로 계층을 그리므로 순서가 곧 화면 순서다. */
export function flattenCategories(nodes: BrowseCategory[]): BrowseCategory[] {
  return nodes.flatMap((node) => [node, ...flattenCategories(node.children ?? [])]);
}

/**
 * 최상위부터 그 노드까지의 이름 경로. 못 찾으면 빈 배열이다.
 *
 * <p>서버가 주는 {@code depth} 만으로는 "무엇 밑의 무엇인지"를 못 그린다 — 같은 depth 의
 * 형제가 여럿이라서다. 그래서 트리를 실제로 타고 내려가며 조상을 모은다.
 */
export function categoryTrail(nodes: BrowseCategory[], id: number): BrowseCategory[] {
  for (const node of nodes) {
    if (node.id === id) return [node];
    const deeper = categoryTrail(node.children ?? [], id);
    if (deeper.length > 0) return [node, ...deeper];
  }
  return [];
}
