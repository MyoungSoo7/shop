import { describe, it, expect } from 'vitest';
import { toggleFacetValue, toOptionTokens, countSelected, type FacetSelection } from '@/api/facet';

/**
 * 선택 상태 조작 규칙.
 *
 * <p>서버의 의미 규칙(축 내 OR / 축 간 AND)은 백엔드가 소유한다. 화면이 지켜야 할 건
 * "선택 상태를 토큰으로 정확히 옮긴다" 와 "빈 축을 남기지 않는다" 두 가지다.
 */
describe('파셋 선택 상태', () => {
  it('없던 값을 켜면 축에 추가된다', () => {
    expect(toggleFacetValue({}, '색상', '빨강')).toEqual({ 색상: ['빨강'] });
  });

  it('같은 축에 값을 더 켜면 나란히 쌓인다 (축 내 OR)', () => {
    const first = toggleFacetValue({}, '색상', '빨강');

    expect(toggleFacetValue(first, '색상', '파랑')).toEqual({ 색상: ['빨강', '파랑'] });
  });

  it('켜져 있던 값을 다시 누르면 꺼진다', () => {
    const selection: FacetSelection = { 색상: ['빨강', '파랑'] };

    expect(toggleFacetValue(selection, '색상', '빨강')).toEqual({ 색상: ['파랑'] });
  });

  it('축의 마지막 값을 끄면 축 자체를 지운다 — 빈 배열을 남기면 "선택 없음" 과 구분되지 않는다', () => {
    const selection: FacetSelection = { 색상: ['빨강'], 사이즈: ['L'] };

    const next = toggleFacetValue(selection, '색상', '빨강');

    expect(next).toEqual({ 사이즈: ['L'] });
    expect('색상' in next).toBe(false);
  });

  it('원본을 바꾸지 않는다', () => {
    const selection: FacetSelection = { 색상: ['빨강'] };

    toggleFacetValue(selection, '색상', '파랑');

    expect(selection).toEqual({ 색상: ['빨강'] });
  });

  it('토큰은 축:값 형식으로 편다', () => {
    expect(toOptionTokens({ 색상: ['빨강', '파랑'], 사이즈: ['L'] }))
      .toEqual(['색상:빨강', '색상:파랑', '사이즈:L']);
  });

  it('빈 선택은 빈 토큰 목록이다', () => {
    expect(toOptionTokens({})).toEqual([]);
    expect(countSelected({})).toBe(0);
  });

  it('선택 개수는 축을 가로질러 센다', () => {
    expect(countSelected({ 색상: ['빨강', '파랑'], 사이즈: ['L'] })).toBe(3);
  });
});
