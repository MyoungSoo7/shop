import { describe, it, expect } from 'vitest';
import {
  categorize,
  warmthOf,
  tempScore,
  situationScore,
  budgetScore,
  reputationScore,
  computeCouponDiscount,
  priceScore,
  seasonToTemp,
  buildOutfits,
  recommend,
  median,
  type RecoProduct,
  type ScoredProduct,
  type FashionCategory,
} from '@/lib/fashionRecommend';

describe('categorize — 상품명 키워드로 카테고리 판별', () => {
  it('키워드로 5개 카테고리를 판별한다', () => {
    expect(categorize('노스페이스 눕시 다운 패딩')).toBe('OUTER');
    expect(categorize('히트텍 크루넥 니트')).toBe('TOP');
    expect(categorize('무탠다드 4-way 슬랙스')).toBe('BOTTOM');
    expect(categorize('뉴발란스 993 스니커즈')).toBe('SHOES');
    expect(categorize('MLB 뉴욕양키스 볼캡')).toBe('ACC');
  });

  it('조거팬츠는 신발 브랜드보다 하의로 우선 판별된다', () => {
    expect(categorize('나이키 클럽 조거팬츠')).toBe('BOTTOM');
  });

  it('비패션 상품은 null 로 제외된다', () => {
    expect(categorize('애플 맥북 프로 14인치')).toBeNull();
    expect(categorize('삼성 갤럭시 S25 울트라')).toBeNull();
  });
});

describe('tempScore — 기온 구간 가중치 경계', () => {
  it('5℃ 이하는 두꺼운 옷 가점·얇은 옷 감점', () => {
    expect(tempScore('HEAVY', 5)).toBe(20);
    expect(tempScore('LIGHT', 5)).toBe(-20);
    expect(tempScore('MID', 5)).toBe(0);
  });

  it('23℃ 이상은 반대로 얇은 옷 가점·두꺼운 옷 감점', () => {
    expect(tempScore('LIGHT', 23)).toBe(20);
    expect(tempScore('HEAVY', 23)).toBe(-20);
  });

  it('선선한 구간(13~22℃)은 중간 보온이 최적', () => {
    expect(tempScore('MID', 18)).toBe(5);
  });

  it('warmthOf 는 키워드로 보온 등급을 매긴다', () => {
    expect(warmthOf('노스페이스 눕시 다운 패딩')).toBe('HEAVY');
    expect(warmthOf('쿨코튼 크루넥 반팔티')).toBe('LIGHT');
    expect(warmthOf('폴로 랄프로렌 옥스포드 셔츠')).toBe('MID');
  });
});

describe('budgetScore — 예산 초과율 비례 감점', () => {
  it('예산 이내면 +10', () => {
    expect(budgetScore(50000, 100000)).toBe(10);
    expect(budgetScore(100000, 100000)).toBe(10);
  });

  it('초과 시 초과율에 비례해 감점되고 -40 이 하한', () => {
    expect(budgetScore(125000, 100000)).toBeCloseTo(-20); // 25% 초과 → -20
    expect(budgetScore(300000, 100000)).toBe(-40); // 200% 초과 → 하한
  });
});

describe('reputationScore — 리뷰 평판 정규화', () => {
  it('리뷰가 없으면 중립(0)', () => {
    expect(reputationScore(undefined)).toBe(0);
    expect(reputationScore({ count: 0, average: 0 })).toBe(0);
  });

  it('충분한 고평점은 최대 +15, 저평점은 -15', () => {
    expect(reputationScore({ count: 10, average: 5 })).toBe(15);
    expect(reputationScore({ count: 10, average: 1 })).toBe(-15);
    expect(reputationScore({ count: 10, average: 3 })).toBe(0);
  });

  it('리뷰 개수가 적으면 신뢰도가 낮아 가점이 줄어든다', () => {
    const few = reputationScore({ count: 1, average: 5 });
    const many = reputationScore({ count: 10, average: 5 });
    expect(few).toBeLessThan(many);
    expect(few).toBeGreaterThan(0);
  });
});

describe('computeCouponDiscount / priceScore — 가격 실체 축', () => {
  it('정액·정률 쿠폰 중 최대 할인을 고르고 minOrderAmount 미만이면 제외', () => {
    const coupons = [
      { code: 'A', type: 'FIXED' as const, discountValue: 5000, minOrderAmount: 0, isActive: true },
      { code: 'B', type: 'PERCENTAGE' as const, discountValue: 10, minOrderAmount: 0, isActive: true },
      { code: 'C', type: 'FIXED' as const, discountValue: 99999, minOrderAmount: 500000, isActive: true },
    ];
    // 100,000 → A=5000, B=10000, C 제외 → 최대 10000
    expect(computeCouponDiscount(100000, coupons)).toBe(10000);
  });

  it('비활성 쿠폰은 무시한다', () => {
    const coupons = [
      { code: 'X', type: 'FIXED' as const, discountValue: 5000, minOrderAmount: 0, isActive: false },
    ];
    expect(computeCouponDiscount(100000, coupons)).toBe(0);
  });

  it('중앙값보다 저렴하면 가점, 비싸면 감점', () => {
    expect(priceScore(80000, 100000)).toBeGreaterThan(0);
    expect(priceScore(150000, 100000)).toBeLessThan(0);
  });

  it('median 은 짝수 길이에서 두 중앙값의 평균', () => {
    expect(median([10, 20, 30, 40])).toBe(25);
    expect(median([])).toBe(0);
  });
});

describe('situationScore / seasonToTemp', () => {
  it('오피스는 슬랙스 가점·조거 감점', () => {
    expect(situationScore('무탠다드 4-way 슬랙스', 'OFFICE')).toBe(8);
    expect(situationScore('나이키 클럽 조거팬츠', 'OFFICE')).toBe(-8);
  });

  it('계절을 대표 기온으로 매핑한다', () => {
    expect(seasonToTemp('WINTER')).toBeLessThan(seasonToTemp('SPRING'));
    expect(seasonToTemp('SUMMER')).toBeGreaterThan(seasonToTemp('FALL'));
  });
});

const FASHION: RecoProduct[] = [
  { id: 1, name: '노스페이스 눕시 다운 패딩', price: 429000 },
  { id: 2, name: '히트텍 크루넥 니트', price: 39900 },
  { id: 3, name: '무탠다드 4-way 슬랙스', price: 49000 },
  { id: 4, name: '리바이스 501 오리지널 데님', price: 118000 },
  { id: 5, name: '뉴발란스 993 스니커즈', price: 259000 },
  { id: 6, name: '쿨코튼 크루넥 반팔티', price: 29000 },
  { id: 7, name: '애플 맥북 프로 14인치', price: 2990000 }, // 비패션 — 제외 대상
];

describe('recommend — 통합 동작', () => {
  it('비패션 상품을 제외하고 점수 내림차순으로 정렬한다', () => {
    const result = recommend({ products: FASHION, budget: 300000, temperature: 2, situation: 'DAILY' });
    expect(result.items.every((it) => it.product.id !== 7)).toBe(true);
    for (let i = 1; i < result.items.length; i++) {
      expect(result.items[i - 1].score).toBeGreaterThanOrEqual(result.items[i].score);
    }
  });

  it('추운 날엔 패딩이 반팔티보다 높은 점수', () => {
    const result = recommend({ products: FASHION, budget: 500000, temperature: 2, situation: 'DAILY' });
    const padding = result.items.find((it) => it.product.id === 1)!;
    const tee = result.items.find((it) => it.product.id === 6)!;
    expect(padding.score).toBeGreaterThan(tee.score);
  });

  it('honest-stylist 4단 구조를 모든 아이템에 채운다', () => {
    const result = recommend({ products: FASHION, budget: 300000, temperature: 10, situation: 'OFFICE' });
    for (const it of result.items) {
      expect(it.verdict.verdict.length).toBeGreaterThan(0);
      expect(it.verdict.evidence.length).toBeGreaterThanOrEqual(1);
      expect(it.verdict.evidence.length).toBeLessThanOrEqual(3);
      expect(it.verdict.counter.length).toBeGreaterThan(0);
      expect(it.verdict.nextStep.length).toBeGreaterThan(0);
      expect(it.score).toBeGreaterThanOrEqual(0);
      expect(it.score).toBeLessThanOrEqual(100);
    }
  });

  it('temperature 가 null 이면 season 폴백을 사용한다', () => {
    const result = recommend({ products: FASHION, budget: 300000, temperature: null, season: 'SUMMER', situation: 'DAILY' });
    expect(result.usedTemperature).toBe(seasonToTemp('SUMMER'));
  });

  it('쿠폰 할인이 effectivePrice 에 반영된다', () => {
    const coupons = [{ code: 'SALE', type: 'PERCENTAGE' as const, discountValue: 10, minOrderAmount: 0, isActive: true }];
    const result = recommend({ products: FASHION, coupons, budget: 500000, temperature: 15, situation: 'DAILY' });
    const padding = result.items.find((it) => it.product.id === 1)!;
    expect(padding.couponDiscount).toBeCloseTo(42900);
    expect(padding.effectivePrice).toBeCloseTo(386100);
  });
});

describe('buildOutfits — 3-코디 구성', () => {
  it('상의+하의+(아우터|신발)로 최대 3세트를 만든다', () => {
    const result = recommend({ products: FASHION, budget: 1000000, temperature: 10, situation: 'DAILY' });
    const outfits = result.outfits;
    expect(outfits.length).toBeGreaterThan(0);
    expect(outfits.length).toBeLessThanOrEqual(3);
    for (const set of outfits) {
      const cats = set.items.map((it) => it.category);
      expect(cats).toContain('TOP');
      expect(cats).toContain('BOTTOM');
      expect(set.totalPrice).toBe(set.items.reduce((s, it) => s + it.effectivePrice, 0));
    }
  });

  it('상의나 하의가 없으면 빈 배열', () => {
    const onlyTops: ScoredProduct[] = [
      { product: { id: 1, name: '니트', price: 10000 }, category: 'TOP' as FashionCategory, score: 60, effectivePrice: 10000, couponDiscount: 0, breakdown: { temp: 0, situation: 0, budget: 0, reputation: 0, price: 0 }, verdict: { verdict: '', evidence: [], counter: '', nextStep: '' } },
    ];
    expect(buildOutfits(onlyTops, 100000)).toEqual([]);
  });

  it('세트 합계가 예산을 넘으면 withinBudget=false', () => {
    const result = recommend({ products: FASHION, budget: 50000, temperature: 10, situation: 'DAILY' });
    const over = result.outfits.find((s) => s.totalPrice > 50000);
    if (over) expect(over.withinBudget).toBe(false);
  });
});
