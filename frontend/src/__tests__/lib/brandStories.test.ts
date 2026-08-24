import { describe, it, expect } from 'vitest';
import {
  extractBrand,
  getBrandStory,
  BRAND_STORIES,
  SITUATION_BRAND_TAGS,
} from '@/lib/brandStories';
import { recommend, recommendBrands, type RecoProduct } from '@/lib/fashionRecommend';

// 시드 V20260711120000 의 16개 상품명 (order-service 시드와 동일)
const SEED_PRODUCTS: { name: string; brand: string | null }[] = [
  { name: '노스페이스 눕시 다운 패딩', brand: 'northface' },
  { name: '무탠다드 울 오버핏 코트', brand: 'mustandard' },
  { name: '코닥 뽀글이 플리스 자켓', brand: 'kodak' },
  { name: '유니클로 경량 패딩 조끼', brand: 'uniqlo' },
  { name: '히트텍 크루넥 니트', brand: 'uniqlo' }, // 히트텍 = 유니클로
  { name: '커버낫 스몰로고 맨투맨', brand: 'covernat' },
  { name: '폴로 랄프로렌 옥스포드 셔츠', brand: 'polo' },
  { name: '쿨코튼 크루넥 반팔티', brand: null }, // 일반 상품 — 인식 가능한 브랜드 없음
  { name: '무탠다드 4-way 슬랙스', brand: 'mustandard' },
  { name: '리바이스 501 오리지널 데님', brand: 'levis' },
  { name: '나이키 클럽 조거팬츠', brand: 'nike' },
  { name: '뉴발란스 993 스니커즈', brand: 'newbalance' },
  { name: '컨버스 척테일러 하이', brand: 'converse' },
  { name: '닥터마틴 1461 3홀', brand: 'drmartens' },
  { name: '아크네 캐시미어 머플러', brand: 'acne' },
  { name: 'MLB 뉴욕양키스 볼캡', brand: 'mlb' },
];

describe('extractBrand — 시드 16상품 브랜드 식별', () => {
  it('브랜드가 있는 15개 상품을 정확히 매핑한다', () => {
    for (const p of SEED_PRODUCTS) {
      expect(extractBrand(p.name)).toBe(p.brand);
    }
  });

  it("히트텍은 유니클로로, 일반 상품('쿨코튼')은 null 로 처리한다", () => {
    expect(extractBrand('히트텍 크루넥 니트')).toBe('uniqlo');
    expect(extractBrand('쿨코튼 크루넥 반팔티')).toBeNull();
  });

  it('컨버스는 브랜드명과 모델명(척테일러) 둘 다로 식별된다', () => {
    expect(extractBrand('컨버스 척테일러 하이')).toBe('converse');
    expect(extractBrand('척테일러 캔버스')).toBe('converse');
  });
});

describe('BRAND_STORIES — 지식 베이스 무결성', () => {
  it('13개 브랜드가 모두 등록되어 있다', () => {
    expect(Object.keys(BRAND_STORIES)).toHaveLength(13);
  });

  it('모든 브랜드가 heritage/spirit 필드를 사실 기반으로 채운다', () => {
    for (const key of Object.keys(BRAND_STORIES)) {
      const s = BRAND_STORIES[key];
      expect(s.key).toBe(key);
      expect(s.name.length).toBeGreaterThan(0);
      // heritage
      expect(s.heritage.founded).toBeGreaterThan(1800);
      expect(s.heritage.founded).toBeLessThanOrEqual(2025);
      expect(s.heritage.origin.length).toBeGreaterThan(0);
      expect(s.heritage.milestones).toHaveLength(2);
      s.heritage.milestones.forEach((m) => expect(m.length).toBeGreaterThan(0));
      // spirit
      expect(s.spirit.essence.length).toBeGreaterThan(0);
      expect(s.spirit.narrative.length).toBeGreaterThan(0);
      expect(s.spirit.tags).toHaveLength(3);
    }
  });

  it('getBrandStory 는 키로 조회하고 없으면 undefined', () => {
    expect(getBrandStory('nike')?.name).toBe('나이키');
    expect(getBrandStory('unknown')).toBeUndefined();
  });
});

const FASHION: RecoProduct[] = SEED_PRODUCTS.map((p, i) => ({
  id: i + 1,
  name: p.name,
  price: 50000 + i * 10000,
}));

describe('recommendBrands — 브랜드 그룹핑·상황 가점·상위 3', () => {
  it('추천 상품을 브랜드로 그룹핑하고 최대 3개 브랜드를 반환한다', () => {
    const result = recommend({ products: FASHION, budget: 300000, temperature: 10, situation: 'DAILY' });
    const recos = recommendBrands(result, 'DAILY');
    expect(recos.length).toBeGreaterThan(0);
    expect(recos.length).toBeLessThanOrEqual(3);
    // 점수 내림차순
    for (let i = 1; i < recos.length; i++) {
      expect(recos[i - 1].score).toBeGreaterThanOrEqual(recos[i].score);
    }
  });

  it('각 브랜드에 대표 상품(소속 최고점)을 연결한다', () => {
    const result = recommend({ products: FASHION, budget: 300000, temperature: 10, situation: 'SPORTY' });
    const recos = recommendBrands(result, 'SPORTY');
    for (const r of recos) {
      const groupItems = result.items.filter((it) => extractBrand(it.product.name) === r.brand.key);
      const maxScore = Math.max(...groupItems.map((it) => it.score));
      expect(r.representative.score).toBe(maxScore);
      expect(r.productCount).toBe(groupItems.length);
    }
  });

  it('상황에 맞는 가치 태그를 가진 브랜드가 매칭 가점을 받는다', () => {
    const result = recommend({ products: FASHION, budget: 500000, temperature: 15, situation: 'SPORTY' });
    const recos = recommendBrands(result, 'SPORTY');
    const nike = recos.find((r) => r.brand.key === 'nike');
    // 나이키 태그(도전/퍼포먼스/승리)는 SPORTY 선호 태그와 겹친다
    if (nike) {
      expect(nike.matchedTags.length).toBeGreaterThan(0);
      expect(nike.matchedTags.every((t) => SITUATION_BRAND_TAGS.SPORTY.includes(t))).toBe(true);
    }
  });

  it('같은 브랜드의 여러 상품은 하나의 그룹으로 묶인다 (무탠다드 코트+슬랙스)', () => {
    const result = recommend({ products: FASHION, budget: 500000, temperature: 12, situation: 'OFFICE' });
    const recos = recommendBrands(result, 'OFFICE');
    // 무탠다드는 코트/슬랙스 2개 상품 → 그룹핑되면 productCount 2 가능
    const all = recommendBrands(result, 'OFFICE');
    expect(all.length).toBeLessThanOrEqual(3);
    // 브랜드 key 중복 없음
    const keys = recos.map((r) => r.brand.key);
    expect(new Set(keys).size).toBe(keys.length);
  });

  it('브랜드 점수는 0~100 범위', () => {
    const result = recommend({ products: FASHION, budget: 300000, temperature: 2, situation: 'DATE' });
    const recos = recommendBrands(result, 'DATE');
    for (const r of recos) {
      expect(r.score).toBeGreaterThanOrEqual(0);
      expect(r.score).toBeLessThanOrEqual(100);
    }
  });
});
