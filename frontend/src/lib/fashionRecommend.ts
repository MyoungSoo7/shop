/**
 * 패션 상품 추천 엔진 — 무신사 해커톤 플러그인(honest-stylist / style-match /
 * budget-shopper / price-navigator / brand-scout)의 판정 축을 프론트 순수 함수로 이식.
 *
 * 설계 원칙: 전 함수 결정적(deterministic) — Date.now()·랜덤 금지, 모든 컨텍스트는
 * 입력 파라미터로 받는다. 종합점수 0~100 + honest-stylist 4단 신뢰 구조를 산출한다.
 */

import { extractBrand, getBrandStory, SITUATION_BRAND_TAGS, type BrandStory } from './brandStories';

export type FashionCategory = 'OUTER' | 'TOP' | 'BOTTOM' | 'SHOES' | 'ACC';
export type StyleSituation = 'DAILY' | 'OFFICE' | 'DATE' | 'SPORTY';
export type Season = 'WINTER' | 'SPRING' | 'SUMMER' | 'FALL';

/** 상품 최소 형태 (ProductResponse 의 부분집합) */
export interface RecoProduct {
  id: number;
  name: string;
  price: number;
}

/** 상품별 리뷰 집계 (평판 축 입력) */
export interface ReviewStat {
  count: number;
  average: number; // 1~5
}

/** 쿠폰 최소 형태 (CouponResponse 의 부분집합) */
export interface RecoCoupon {
  code: string;
  type: 'FIXED' | 'PERCENTAGE';
  discountValue: number;
  minOrderAmount: number;
  isActive: boolean;
}

export interface RecommendInput {
  products: RecoProduct[];
  reviews?: Record<number, ReviewStat>;
  coupons?: RecoCoupon[];
  budget: number;
  /** 실측 기온(℃). null 이면 season 으로 대체 */
  temperature: number | null;
  /** temperature 가 null 일 때의 폴백 계절 */
  season?: Season;
  situation: StyleSituation;
}

/** honest-stylist 4단 신뢰 구조 */
export interface HonestVerdict {
  verdict: string;
  evidence: string[];
  counter: string;
  nextStep: string;
}

export interface ScoreBreakdown {
  temp: number;
  situation: number;
  budget: number;
  reputation: number;
  price: number;
}

export interface ScoredProduct {
  product: RecoProduct;
  category: FashionCategory;
  score: number;
  effectivePrice: number;
  couponDiscount: number;
  breakdown: ScoreBreakdown;
  verdict: HonestVerdict;
}

export interface OutfitSet {
  label: string;
  items: ScoredProduct[];
  totalPrice: number;
  withinBudget: boolean;
  /** 세트 합계 / 예산 (0~) */
  budgetRatio: number;
}

export interface RecommendResult {
  items: ScoredProduct[];
  outfits: OutfitSet[];
  usedTemperature: number;
  disclaimer: string;
}

export const DISCLAIMER =
  '본 추천은 참고 정보이며 구매 판단의 책임은 이용자 본인에게 있습니다.';

/* ─────────────────────────────────────────
   1. 카테고리 판별 (상품명 키워드 규칙)
───────────────────────────────────────── */

/** 우선순위 순서대로 첫 매치가 카테고리를 결정한다 (겹침 방지) */
const CATEGORY_RULES: { category: FashionCategory; keywords: string[] }[] = [
  { category: 'OUTER', keywords: ['패딩', '코트', '자켓', '재킷', '조끼', '플리스', '파카', '무스탕', '점퍼', '야상'] },
  { category: 'BOTTOM', keywords: ['슬랙스', '데님', '청바지', '조거', '팬츠', '치노', '반바지', '슬랙'] },
  { category: 'TOP', keywords: ['니트', '맨투맨', '셔츠', '반팔', '후드', '티셔츠', '스웨트', '블라우스', '가디건', '반팔티'] },
  { category: 'SHOES', keywords: ['스니커즈', '운동화', '신발', '부츠', '로퍼', '슈즈', '뉴발란스', '컨버스', '척테일러', '닥터마틴', '반스', '아디다스'] },
  { category: 'ACC', keywords: ['머플러', '볼캡', '캡', '모자', '목도리', '가방', '벨트', '양말', '장갑', '비니', '스카프'] },
];

/** 패션 상품이면 카테고리, 아니면 null (비패션 상품 제외) */
export function categorize(name: string): FashionCategory | null {
  for (const rule of CATEGORY_RULES) {
    if (rule.keywords.some((k) => name.includes(k))) {
      return rule.category;
    }
  }
  return null;
}

/* ─────────────────────────────────────────
   2. 보온 등급 + 날씨 가중치 축
───────────────────────────────────────── */

export type Warmth = 'HEAVY' | 'MID' | 'LIGHT';

const HEAVY_KEYWORDS = ['패딩', '코트', '니트', '플리스', '파카', '무스탕', '머플러', '목도리', '부츠', '기모', '울', '스웨터', '캐시미어'];
const LIGHT_KEYWORDS = ['반팔', '반바지', '린넨', '쿨', '나시', '크롭', '매쉬', '냉감'];

export function warmthOf(name: string): Warmth {
  if (HEAVY_KEYWORDS.some((k) => name.includes(k))) return 'HEAVY';
  if (LIGHT_KEYWORDS.some((k) => name.includes(k))) return 'LIGHT';
  return 'MID';
}

/** 계절 → 대표 기온(℃) */
export function seasonToTemp(season: Season): number {
  switch (season) {
    case 'WINTER': return 2;
    case 'SPRING': return 16;
    case 'SUMMER': return 28;
    case 'FALL': return 15;
  }
}

/**
 * 기온 구간 × 보온 등급 → 가중치(-20 ~ +20).
 * ≤5° 두꺼운 옷 가점·얇은 옷 감점 / ≥23° 반대.
 */
export function tempScore(warmth: Warmth, temp: number): number {
  if (temp <= 5) {
    return warmth === 'HEAVY' ? 20 : warmth === 'LIGHT' ? -20 : 0;
  }
  if (temp <= 12) {
    return warmth === 'HEAVY' ? 12 : warmth === 'LIGHT' ? -12 : 3;
  }
  if (temp <= 22) {
    // 선선~쾌적: 중간 보온이 가장 적합
    return warmth === 'MID' ? 5 : warmth === 'HEAVY' ? -2 : 2;
  }
  // temp >= 23 (더움)
  return warmth === 'LIGHT' ? 20 : warmth === 'HEAVY' ? -20 : 0;
}

/* ─────────────────────────────────────────
   3. 코디 상황(TPO) 축
───────────────────────────────────────── */

const SITUATION_PREF: Record<StyleSituation, { fav: string[]; avoid: string[] }> = {
  OFFICE: { fav: ['코트', '슬랙스', '셔츠', '니트', '로퍼', '블레이저'], avoid: ['조거', '스니커즈', '후드', '반바지', '트레이닝'] },
  SPORTY: { fav: ['조거', '스니커즈', '후드', '플리스', '트레이닝', '운동화', '반팔'], avoid: ['슬랙스', '코트', '셔츠', '로퍼'] },
  DATE: { fav: ['코트', '니트', '셔츠', '머플러', '블라우스', '슬랙스'], avoid: ['트레이닝', '조거', '반바지'] },
  DAILY: { fav: ['데님', '맨투맨', '스니커즈', '후드', '티'], avoid: [] },
};

/** 상황 적합 가중치(-8 ~ +8) */
export function situationScore(name: string, situation: StyleSituation): number {
  const pref = SITUATION_PREF[situation];
  let s = 0;
  if (pref.fav.some((k) => name.includes(k))) s += 8;
  if (pref.avoid.some((k) => name.includes(k))) s -= 8;
  return s;
}

/* ─────────────────────────────────────────
   4. 예산 축 (budget-shopper)
───────────────────────────────────────── */

/** 예산 내면 +10, 초과 시 초과율 비례 감점(-40 하한) */
export function budgetScore(price: number, budget: number): number {
  if (budget <= 0) return 0;
  if (price <= budget) return 10;
  const over = (price - budget) / budget;
  return Math.max(-40, -over * 80);
}

/* ─────────────────────────────────────────
   5. 평판 축 (brand-scout 대용 — 리뷰 평점·개수)
───────────────────────────────────────── */

/** 리뷰 평점 평균·개수 정규화 가점(-15 ~ +15). 리뷰 없으면 0(중립) */
export function reputationScore(stat: ReviewStat | undefined): number {
  if (!stat || stat.count <= 0) return 0;
  const confidence = Math.min(1, stat.count / 5); // 5건 이상이면 완전 신뢰
  const raw = (stat.average - 3) * 7.5 * confidence;
  return clamp(raw, -15, 15);
}

/* ─────────────────────────────────────────
   6. 가격 실체 축 (price-navigator) + 쿠폰
───────────────────────────────────────── */

/** 적용 가능한 쿠폰 중 최대 할인액 (원) */
export function computeCouponDiscount(price: number, coupons: RecoCoupon[] | undefined): number {
  if (!coupons || coupons.length === 0) return 0;
  let best = 0;
  for (const c of coupons) {
    if (!c.isActive) continue;
    if (price < c.minOrderAmount) continue;
    const d = c.type === 'FIXED' ? c.discountValue : (price * c.discountValue) / 100;
    if (d > best) best = d;
  }
  return Math.min(best, price);
}

/**
 * 같은 카테고리 가격 분포에서의 위치(-10 ~ +15).
 * 쿠폰 적용가(effectivePrice)가 중앙값보다 저렴하면 가점.
 */
export function priceScore(effectivePrice: number, categoryMedian: number): number {
  if (categoryMedian <= 0) return 0;
  const ratio = effectivePrice / categoryMedian;
  return clamp((1 - ratio) * 20, -10, 15);
}

/* ─────────────────────────────────────────
   7. honest-stylist 4단 신뢰 구조
───────────────────────────────────────── */

const CATEGORY_LABEL: Record<FashionCategory, string> = {
  OUTER: '아우터', TOP: '상의', BOTTOM: '하의', SHOES: '신발', ACC: '액세서리',
};

function verdictLine(score: number): string {
  if (score >= 75) return '지금 조건에 잘 맞는 추천 — 담아둘 만합니다';
  if (score >= 55) return '무난한 선택 — 날씨·예산 조건은 충족합니다';
  if (score >= 40) return '보류 권고 — 더 맞는 후보가 있습니다';
  return '이번 조건엔 부적합 — 다른 상황에서 다시 보세요';
}

function buildVerdict(
  category: FashionCategory,
  score: number,
  temp: number,
  b: ScoreBreakdown,
  effectivePrice: number,
  couponDiscount: number,
  stat: ReviewStat | undefined,
  budget: number,
): HonestVerdict {
  const evidence: string[] = [];

  // 날씨 근거
  if (b.temp > 0) {
    evidence.push(`기온 ${temp}℃ 기준 ${CATEGORY_LABEL[category]} 적합 (+${b.temp})`);
  } else if (b.temp < 0) {
    evidence.push(`기온 ${temp}℃ 기준 계절 불일치 (${b.temp})`);
  }

  // 예산 근거
  if (effectivePrice <= budget) {
    evidence.push(`예산 ${fmtWon(budget)} 이내 (구매가 ${fmtWon(effectivePrice)})`);
  } else {
    evidence.push(`예산 ${fmtWon(budget)} 초과 (구매가 ${fmtWon(effectivePrice)}, ${b.budget.toFixed(0)}점 감점)`);
  }

  // 가격/쿠폰 근거
  if (couponDiscount > 0) {
    evidence.push(`쿠폰 ${fmtWon(couponDiscount)} 할인 반영`);
  } else if (b.price > 0) {
    evidence.push('동일 카테고리 중앙값 대비 저렴한 편');
  }

  // 평판 근거
  if (stat && stat.count > 0) {
    evidence.push(`리뷰 ${stat.count}건 평균 ${stat.average.toFixed(1)}점`);
  } else {
    evidence.push('리뷰 없음 — 평판 축은 중립 처리');
  }

  return {
    verdict: verdictLine(score),
    evidence: evidence.slice(0, 3),
    counter: '실물 핏·소재 질감·색감은 데이터 밖이며, 가격·재고는 조회 시점 기준입니다.',
    nextStep:
      score >= 55
        ? '상품을 눌러 주문 화면에서 리뷰·재고를 최종 확인하세요.'
        : '3-코디 제안에서 빈 카테고리를 먼저 채우는 편을 권합니다.',
  };
}

/* ─────────────────────────────────────────
   8. 3-코디 매칭 (style-match)
───────────────────────────────────────── */

function pick(items: ScoredProduct[], category: FashionCategory): ScoredProduct[] {
  return items.filter((it) => it.category === category);
}

/**
 * 추천 상위 아이템으로 상의+하의+(아우터|신발) 조합 최대 3세트 생성.
 * 상의·하의가 모두 있어야 세트를 만든다.
 */
export function buildOutfits(items: ScoredProduct[], budget: number): OutfitSet[] {
  const tops = pick(items, 'TOP');
  const bottoms = pick(items, 'BOTTOM');
  const extras = [...pick(items, 'OUTER'), ...pick(items, 'SHOES')].sort((a, b) => b.score - a.score);

  if (tops.length === 0 || bottoms.length === 0) return [];

  const setCount = Math.min(3, Math.max(tops.length, bottoms.length));
  const outfits: OutfitSet[] = [];
  const labels = ['데일리 코어', '분위기 전환', '포인트 세트'];

  for (let i = 0; i < setCount; i++) {
    const top = tops[i % tops.length];
    const bottom = bottoms[i % bottoms.length];
    const setItems: ScoredProduct[] = [top, bottom];
    if (extras.length > 0) {
      setItems.push(extras[i % extras.length]);
    }
    const totalPrice = setItems.reduce((s, it) => s + it.effectivePrice, 0);
    outfits.push({
      label: labels[i] ?? `코디 ${i + 1}`,
      items: setItems,
      totalPrice,
      withinBudget: budget > 0 ? totalPrice <= budget : false,
      budgetRatio: budget > 0 ? totalPrice / budget : 0,
    });
  }
  return outfits;
}

/* ─────────────────────────────────────────
   9. 메인 엔트리
───────────────────────────────────────── */

export function recommend(input: RecommendInput): RecommendResult {
  const usedTemperature =
    input.temperature !== null && input.temperature !== undefined
      ? input.temperature
      : seasonToTemp(input.season ?? 'SPRING');

  // 패션 상품만 추린 뒤 카테고리 부여
  const withCategory = input.products
    .map((product) => ({ product, category: categorize(product.name) }))
    .filter((x): x is { product: RecoProduct; category: FashionCategory } => x.category !== null);

  // 카테고리별 중앙값 (가격 축 기준)
  const medians = categoryMedians(withCategory);

  const scored: ScoredProduct[] = withCategory.map(({ product, category }) => {
    const stat = input.reviews?.[product.id];
    const couponDiscount = computeCouponDiscount(product.price, input.coupons);
    const effectivePrice = product.price - couponDiscount;

    const breakdown: ScoreBreakdown = {
      temp: tempScore(warmthOf(product.name), usedTemperature),
      situation: situationScore(product.name, input.situation),
      budget: budgetScore(effectivePrice, input.budget),
      reputation: reputationScore(stat),
      price: priceScore(effectivePrice, medians[category]),
    };

    const raw =
      50 +
      breakdown.temp +
      breakdown.situation +
      breakdown.budget +
      breakdown.reputation +
      breakdown.price;
    const score = Math.round(clamp(raw, 0, 100));

    const verdict = buildVerdict(
      category, score, usedTemperature, breakdown,
      effectivePrice, couponDiscount, stat, input.budget,
    );

    return { product, category, score, effectivePrice, couponDiscount, breakdown, verdict };
  });

  scored.sort((a, b) => b.score - a.score || a.effectivePrice - b.effectivePrice);

  return {
    items: scored,
    outfits: buildOutfits(scored, input.budget),
    usedTemperature,
    disclaimer: DISCLAIMER,
  };
}

/* ─────────────────────────────────────────
   10. 브랜드 추천 (히스토리 + 영적 마케팅 축)
───────────────────────────────────────── */

export interface BrandReco {
  brand: BrandStory;
  /** 브랜드 종합점수 0~100 = 소속 상품 평균(60%) + 상황×가치태그 매칭(40%) */
  score: number;
  /** 상황 선호 태그와 겹친 브랜드 가치 태그 */
  matchedTags: string[];
  /** 이 정신이 담긴 대표 추천 상품 (소속 상품 중 최고점) */
  representative: ScoredProduct;
  /** 소속 추천 상품 수 */
  productCount: number;
}

/**
 * 추천 상품 결과를 브랜드로 그룹핑해 상위 3개 브랜드를 산출.
 * 점수 = 소속 상품 평균점수 × 0.6 + (매칭 태그 수 / 3) × 40.
 * 브랜드를 식별할 수 없는 상품(일반 상품)은 그룹핑에서 제외한다.
 */
export function recommendBrands(result: RecommendResult, situation: StyleSituation): BrandReco[] {
  const situationTags = SITUATION_BRAND_TAGS[situation];

  // 브랜드 key 별로 소속 추천 상품 그룹핑
  const groups = new Map<string, ScoredProduct[]>();
  for (const item of result.items) {
    const key = extractBrand(item.product.name);
    if (!key) continue;
    const list = groups.get(key) ?? [];
    list.push(item);
    groups.set(key, list);
  }

  const recos: BrandReco[] = [];
  for (const [key, items] of groups) {
    const brand = getBrandStory(key);
    if (!brand) continue;

    const avgScore = items.reduce((s, it) => s + it.score, 0) / items.length;
    const matchedTags = brand.spirit.tags.filter((t) => situationTags.includes(t));
    const tagBonus = (matchedTags.length / 3) * 40; // 태그 3개 기준
    const score = Math.round(clamp(avgScore * 0.6 + tagBonus, 0, 100));

    const representative = items.reduce((best, it) => (it.score > best.score ? it : best), items[0]);

    recos.push({ brand, score, matchedTags, representative, productCount: items.length });
  }

  recos.sort((a, b) => b.score - a.score || b.productCount - a.productCount);
  return recos.slice(0, 3);
}

/* ─────────────────────────────────────────
   유틸
───────────────────────────────────────── */

export function clamp(v: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, v));
}

function categoryMedians(
  items: { product: RecoProduct; category: FashionCategory }[],
): Record<FashionCategory, number> {
  const buckets: Record<FashionCategory, number[]> = {
    OUTER: [], TOP: [], BOTTOM: [], SHOES: [], ACC: [],
  };
  for (const { product, category } of items) {
    buckets[category].push(product.price);
  }
  const out = {} as Record<FashionCategory, number>;
  (Object.keys(buckets) as FashionCategory[]).forEach((cat) => {
    out[cat] = median(buckets[cat]);
  });
  return out;
}

export function median(values: number[]): number {
  if (values.length === 0) return 0;
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[mid - 1] + sorted[mid]) / 2 : sorted[mid];
}

function fmtWon(v: number): string {
  return `${Math.round(v).toLocaleString('ko-KR')}원`;
}

export const CATEGORY_LABELS = CATEGORY_LABEL;
