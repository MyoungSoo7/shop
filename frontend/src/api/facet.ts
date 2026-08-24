import api from './axios';
import type { ProductResponse } from '@/types';

/** 파셋 한 값 — 이 값을 (추가로) 고르면 남는 상품 수와, 지금 선택돼 있는지. */
export interface FacetValue {
  code: string;
  name: string;
  productCount: number;
  /** 선택 여부는 서버 판단을 그대로 쓴다 — 화면이 따로 계산하면 두 상태가 어긋난다. */
  selected: boolean;
}

export interface Facet {
  axisCode: string;
  axisName: string;
  values: FacetValue[];
}

export interface FacetSearchResponse {
  products: ProductResponse[];
  facets: Facet[];
}

/** 선택 상태 — 축 코드 → 고른 값 코드들. 같은 축 안은 OR, 축 간은 AND 로 서버가 해석한다. */
export type FacetSelection = Record<string, string[]>;

/**
 * 선택 상태를 {@code option=축:값} 토큰 목록으로 편다.
 *
 * <p>axios 기본 직렬화는 배열을 {@code option[]=...} 로 만들어 서버의 {@code @RequestParam("option")}
 * 과 어긋난다. 그래서 쿼리 문자열을 직접 만든다.
 */
export function toOptionTokens(selection: FacetSelection): string[] {
  return Object.entries(selection).flatMap(([axis, values]) =>
    values.map((value) => `${axis}:${value}`),
  );
}

export const facetApi = {
  /**
   * 옵션 파셋 검색.
   * GET /api/products/facets?option=축:값&...
   */
  search: async (
    selection: FacetSelection,
    options?: { categoryId?: number; availableOnly?: boolean },
  ): Promise<FacetSearchResponse> => {
    const params = new URLSearchParams();
    toOptionTokens(selection).forEach((token) => params.append('option', token));
    if (options?.categoryId != null) params.append('categoryId', String(options.categoryId));
    if (options?.availableOnly === false) params.append('availableOnly', 'false');

    const query = params.toString();
    const response = await api.get<FacetSearchResponse>(
      `/api/products/facets${query ? `?${query}` : ''}`,
    );
    return response.data;
  },
};

/** 값 하나를 켜고 끈다. 빈 축은 지워 "선택 없음" 과 "빈 배열" 이 갈리지 않게 한다. */
export function toggleFacetValue(
  selection: FacetSelection,
  axisCode: string,
  valueCode: string,
): FacetSelection {
  const current = selection[axisCode] ?? [];
  const next = current.includes(valueCode)
    ? current.filter((v) => v !== valueCode)
    : [...current, valueCode];

  const updated = { ...selection };
  if (next.length === 0) {
    delete updated[axisCode];
  } else {
    updated[axisCode] = next;
  }
  return updated;
}

export function countSelected(selection: FacetSelection): number {
  return Object.values(selection).reduce((sum, values) => sum + values.length, 0);
}
