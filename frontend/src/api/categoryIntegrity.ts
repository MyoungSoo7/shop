import api from './axios';

/**
 * 카테고리 상품수 캐시 정합 API.
 *
 * <p>{@code product_count} 는 캐시고 정본은 상품↔카테고리 매핑의 실계수다. 캐시는 갱신을 한 번
 * 빠뜨리면 조용히 틀리는데 화면은 여전히 숫자를 보여 주므로 아무도 모른다 — 그래서 세는 표면
 * (점검)과 고치는 표면(재계산)을 <b>따로</b> 둔다. 점검이 조용히 고치면 무엇이 얼마나 어긋나
 * 있었는지가 사라져 갱신을 빠뜨린 경로를 되짚을 수 없다.
 */

export type CategoryCountDriftKind = 'OVERCOUNT' | 'UNDERCOUNT';

export interface CategoryCountDriftSample {
  categoryId: number;
  slug: string;
  name: string;
  cachedCount: number;
  actualCount: number;
  /** 캐시 − 실계수. 부호가 방향, 절대값이 조치 우선순위 */
  difference: number;
  kind: CategoryCountDriftKind;
}

export interface CategoryCountIntegrity {
  /** 전수 규모 — 표본 상한과 무관하다 */
  drifted: number;
  /** 규모 0 그리고 읽을 수 없는 행 0 일 때만 true (서버 판정) */
  healthy: boolean;
  byKind: Partial<Record<CategoryCountDriftKind, number>>;
  samples: CategoryCountDriftSample[];
  /** 도메인이 드리프트로 인정하지 않은 행 수 — 0 이 아니면 조회 조건 자체가 의심스럽다 */
  unreadable: number;
}

export const categoryIntegrityApi = {
  checkCounts: async (sampleLimit?: number): Promise<CategoryCountIntegrity> =>
    (await api.get<CategoryCountIntegrity>('/admin/categories/count-integrity',
      { params: sampleLimit === undefined ? {} : { sampleLimit } })).data,

  /** @returns 갱신된 행 수 */
  refreshCounts: async (): Promise<number> =>
    (await api.post<number>('/admin/categories/refresh-counts')).data,
};
