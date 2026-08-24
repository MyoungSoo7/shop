import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  facetApi,
  toOptionTokens,
  toggleFacetValue,
  countSelected,
  type FacetSelection,
} from '@/api/facet';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
  },
}));

describe('toOptionTokens', () => {
  it('축:값 토큰으로 편다', () => {
    expect(toOptionTokens({ COLOR: ['RED', 'BLUE'], SIZE: ['M'] })).toEqual([
      'COLOR:RED',
      'COLOR:BLUE',
      'SIZE:M',
    ]);
  });

  it('선택이 없으면 빈 배열이다', () => {
    expect(toOptionTokens({})).toEqual([]);
  });
});

describe('toggleFacetValue', () => {
  it('없던 값을 켠다', () => {
    expect(toggleFacetValue({}, 'COLOR', 'RED')).toEqual({ COLOR: ['RED'] });
  });

  it('같은 축에 값을 추가한다', () => {
    expect(toggleFacetValue({ COLOR: ['RED'] }, 'COLOR', 'BLUE')).toEqual({
      COLOR: ['RED', 'BLUE'],
    });
  });

  it('켜져 있던 값을 끄면 그 값만 빠진다', () => {
    expect(toggleFacetValue({ COLOR: ['RED', 'BLUE'] }, 'COLOR', 'RED')).toEqual({
      COLOR: ['BLUE'],
    });
  });

  it('마지막 값을 끄면 축 자체를 지운다 — "선택 없음"과 "빈 배열"이 갈리지 않게', () => {
    const next = toggleFacetValue({ COLOR: ['RED'], SIZE: ['M'] }, 'COLOR', 'RED');

    expect(next).toEqual({ SIZE: ['M'] });
    expect('COLOR' in next).toBe(false);
  });

  it('원본 선택 상태를 변경하지 않는다', () => {
    const original: FacetSelection = { COLOR: ['RED'] };

    toggleFacetValue(original, 'COLOR', 'BLUE');

    expect(original).toEqual({ COLOR: ['RED'] });
  });
});

describe('countSelected', () => {
  it('축을 가로질러 고른 값의 총 개수를 센다', () => {
    expect(countSelected({ COLOR: ['RED', 'BLUE'], SIZE: ['M'] })).toBe(3);
    expect(countSelected({})).toBe(0);
  });
});

describe('facetApi.search', () => {
  beforeEach(() => vi.resetAllMocks());

  it('선택이 없으면 쿼리 없이 호출한다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { products: [], facets: [] } });

    await facetApi.search({});

    expect(api.get).toHaveBeenCalledWith('/api/products/facets');
  });

  it('option 토큰을 반복 파라미터로 붙인다 (option[] 직렬화를 피한다)', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { products: [], facets: [] } });

    await facetApi.search({ COLOR: ['RED', 'BLUE'] });

    expect(api.get).toHaveBeenCalledWith(
      '/api/products/facets?option=COLOR%3ARED&option=COLOR%3ABLUE',
    );
  });

  it('categoryId 를 함께 넘긴다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { products: [], facets: [] } });

    await facetApi.search({ SIZE: ['M'] }, { categoryId: 3 });

    expect(api.get).toHaveBeenCalledWith('/api/products/facets?option=SIZE%3AM&categoryId=3');
  });

  it('availableOnly=false 일 때만 파라미터를 붙인다 (기본값은 서버에 맡긴다)', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({ data: { products: [], facets: [] } });
    await facetApi.search({}, { availableOnly: true });
    expect(api.get).toHaveBeenCalledWith('/api/products/facets');

    vi.mocked(api.get).mockResolvedValueOnce({ data: { products: [], facets: [] } });
    await facetApi.search({}, { availableOnly: false });
    expect(api.get).toHaveBeenLastCalledWith('/api/products/facets?availableOnly=false');
  });

  it('응답의 selected 판정은 서버 값을 그대로 쓴다', async () => {
    vi.mocked(api.get).mockResolvedValueOnce({
      data: {
        products: [],
        facets: [
          {
            axisCode: 'COLOR',
            axisName: '색상',
            values: [{ code: 'RED', name: '빨강', productCount: 3, selected: true }],
          },
        ],
      },
    });

    const result = await facetApi.search({ COLOR: ['RED'] });

    expect(result.facets[0].values[0].selected).toBe(true);
  });
});
