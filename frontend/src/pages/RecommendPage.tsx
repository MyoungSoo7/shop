import React, { useEffect, useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { productApi } from '@/api/product';
import { reviewApi } from '@/api/review';
import { couponApi } from '@/api/coupon';
import { fetchCurrentWeather, describeWeatherCode } from '@/api/weather';
import {
  recommend,
  recommendBrands,
  categorize,
  CATEGORY_LABELS,
  DISCLAIMER,
  type RecommendResult,
  type ScoredProduct,
  type StyleSituation,
  type Season,
  type RecoCoupon,
  type ReviewStat,
  type BrandReco,
} from '@/lib/fashionRecommend';
import Card from '@/components/Card';
import Spinner from '@/components/Spinner';

const SITUATIONS: { value: StyleSituation; label: string; emoji: string }[] = [
  { value: 'DAILY', label: '데일리', emoji: '🧢' },
  { value: 'OFFICE', label: '오피스', emoji: '👔' },
  { value: 'DATE', label: '데이트', emoji: '💕' },
  { value: 'SPORTY', label: '스포티', emoji: '🏃' },
];

const SEASONS: { value: Season; label: string }[] = [
  { value: 'WINTER', label: '겨울' },
  { value: 'SPRING', label: '봄' },
  { value: 'SUMMER', label: '여름' },
  { value: 'FALL', label: '가을' },
];

const fmt = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(Math.round(v));

/** 리뷰를 병렬 조회할 상위 후보 수 (과호출 방지) */
const REVIEW_FETCH_LIMIT = 8;

const scoreBadgeClass = (score: number) => {
  if (score >= 75) return 'bg-green-100 text-green-800';
  if (score >= 55) return 'bg-blue-100 text-blue-800';
  if (score >= 40) return 'bg-yellow-100 text-yellow-800';
  return 'bg-gray-200 text-gray-600';
};

const RecommendPage: React.FC = () => {
  const navigate = useNavigate();

  // ── 원천 데이터 ──
  const [products, setProducts] = useState<{ id: number; name: string; price: number }[]>([]);
  const [coupons, setCoupons] = useState<RecoCoupon[]>([]);
  const [loadingData, setLoadingData] = useState(true);
  const [dataError, setDataError] = useState<string | null>(null);

  // ── 날씨 ──
  const [temperature, setTemperature] = useState<number | null>(null);
  const [weatherCode, setWeatherCode] = useState<number | null>(null);
  const [weatherFailed, setWeatherFailed] = useState(false);
  const [season, setSeason] = useState<Season>('FALL');

  // ── 입력 패널 ──
  const [budget, setBudget] = useState(300000);
  const [situation, setSituation] = useState<StyleSituation>('DAILY');

  // ── 결과 ──
  const [result, setResult] = useState<RecommendResult | null>(null);
  const [brands, setBrands] = useState<BrandReco[]>([]);
  const [computing, setComputing] = useState(false);

  // 상품 + 쿠폰 로딩
  useEffect(() => {
    setLoadingData(true);
    productApi
      .getAvailableProducts()
      .then((list) => {
        // 패션 상품만 유지 (비패션은 카테고리 판별 실패로 제외)
        setProducts(
          list
            .filter((p) => categorize(p.name) !== null)
            .map((p) => ({ id: p.id, name: p.name, price: p.price })),
        );
      })
      .catch(() => setDataError('상품 목록을 불러오지 못했습니다.'))
      .finally(() => setLoadingData(false));

    couponApi
      .getAll()
      .then((list) =>
        setCoupons(
          list.map((c) => ({
            code: c.code,
            type: c.type,
            discountValue: c.discountValue,
            minOrderAmount: c.minOrderAmount,
            isActive: c.isActive,
          })),
        ),
      )
      .catch(() => {
        /* 쿠폰 실패는 무시 — 할인 반영만 생략 */
      });
  }, []);

  // 실시간 날씨
  useEffect(() => {
    fetchCurrentWeather()
      .then((w) => {
        if (w) {
          setTemperature(w.temperature);
          setWeatherCode(w.weatherCode);
        } else {
          setWeatherFailed(true);
        }
      })
      .catch(() => setWeatherFailed(true));
  }, []);

  const fashionCount = products.length;

  const handleRecommend = async () => {
    if (products.length === 0) return;
    setComputing(true);
    try {
      // 1차: 리뷰 없이 랭킹 → 상위 후보만 리뷰 병렬 조회 (과호출 방지)
      const first = recommend({
        products,
        coupons,
        budget,
        temperature: weatherFailed ? null : temperature,
        season,
        situation,
      });
      const topIds = first.items.slice(0, REVIEW_FETCH_LIMIT).map((it) => it.product.id);

      const reviewEntries = await Promise.all(
        topIds.map(async (id) => {
          try {
            const reviews = await reviewApi.getProductReviews(id);
            const count = reviews.length;
            const average = count > 0 ? reviews.reduce((s, r) => s + r.rating, 0) / count : 0;
            return [id, { count, average } as ReviewStat] as const;
          } catch {
            return null;
          }
        }),
      );
      const reviews: Record<number, ReviewStat> = {};
      for (const entry of reviewEntries) {
        if (entry) reviews[entry[0]] = entry[1];
      }

      // 2차: 평판 축 반영 재계산
      const final = recommend({
        products,
        coupons,
        reviews,
        budget,
        temperature: weatherFailed ? null : temperature,
        season,
        situation,
      });
      setResult(final);
      setBrands(recommendBrands(final, situation));
    } finally {
      setComputing(false);
    }
  };

  const weatherLabel = useMemo(() => {
    if (weatherFailed || temperature === null) return null;
    const desc = weatherCode !== null ? describeWeatherCode(weatherCode) : '';
    return `${temperature.toFixed(1)}℃ · ${desc}`;
  }, [temperature, weatherCode, weatherFailed]);

  return (
    <div className="min-h-screen bg-gradient-to-br from-blue-50 to-indigo-50 py-10 px-4 sm:px-6 lg:px-8">
      <div className="max-w-5xl mx-auto">
        <div className="text-center mb-6">
          <h1 className="text-3xl font-bold text-gray-900">추천받기</h1>
          <p className="text-sm text-gray-500 mt-1">
            날씨 · 예산 · 상황 · 평판 · 시장가 5개 축으로 오늘 입을 옷을 골라드립니다.
          </p>
        </div>

        {dataError && (
          <div className="mb-4 bg-red-50 border border-red-200 rounded-lg p-3">
            <p className="text-red-800 text-sm">{dataError}</p>
          </div>
        )}

        {/* ── 입력 패널 ── */}
        <Card title="추천 조건" className="mb-6">
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6">
            {/* 예산 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">
                예산 <span className="text-blue-600 font-semibold">{fmt(budget)}</span>
              </label>
              <input
                type="range"
                min={30000}
                max={1000000}
                step={10000}
                value={budget}
                onChange={(e) => setBudget(Number(e.target.value))}
                className="w-full accent-blue-600"
              />
              <input
                type="number"
                min={0}
                step={10000}
                value={budget}
                onChange={(e) => setBudget(Math.max(0, Number(e.target.value)))}
                className="mt-2 w-full px-3 py-2 border border-gray-300 rounded-lg text-sm focus:ring-2 focus:ring-blue-500 focus:border-blue-500"
              />
            </div>

            {/* 상황 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">스타일 상황</label>
              <div className="grid grid-cols-2 gap-2">
                {SITUATIONS.map((s) => (
                  <button
                    key={s.value}
                    type="button"
                    onClick={() => setSituation(s.value)}
                    className={`px-3 py-2 rounded-lg text-sm font-medium border transition-colors ${
                      situation === s.value
                        ? 'bg-blue-600 text-white border-blue-600'
                        : 'bg-white text-gray-700 border-gray-300 hover:bg-blue-50'
                    }`}
                  >
                    <span className="mr-1">{s.emoji}</span>
                    {s.label}
                  </button>
                ))}
              </div>
            </div>

            {/* 날씨 */}
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">오늘 날씨</label>
              {weatherLabel ? (
                <div className="bg-sky-50 border border-sky-200 rounded-lg p-3">
                  <p className="text-lg font-bold text-sky-800">{weatherLabel}</p>
                  <p className="text-xs text-sky-600 mt-0.5">서울 · 실시간(Open-Meteo)</p>
                </div>
              ) : (
                <div>
                  <p className="text-xs text-gray-400 mb-2">
                    {weatherFailed ? '실시간 날씨 조회 실패 — 계절을 선택하세요.' : '날씨 불러오는 중...'}
                  </p>
                  <div className="grid grid-cols-4 gap-1.5">
                    {SEASONS.map((s) => (
                      <button
                        key={s.value}
                        type="button"
                        onClick={() => setSeason(s.value)}
                        className={`px-2 py-2 rounded-lg text-xs font-medium border transition-colors ${
                          season === s.value
                            ? 'bg-blue-600 text-white border-blue-600'
                            : 'bg-white text-gray-700 border-gray-300 hover:bg-blue-50'
                        }`}
                      >
                        {s.label}
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </div>

          <div className="mt-6 flex items-center justify-between">
            <p className="text-xs text-gray-400">
              {loadingData ? '상품 불러오는 중...' : `패션 상품 ${fashionCount}개 대상`}
            </p>
            {computing ? (
              <Spinner size="sm" message="추천 계산 중..." />
            ) : (
              <button
                onClick={handleRecommend}
                disabled={loadingData || fashionCount === 0}
                className="px-8 py-3 bg-blue-600 text-white rounded-lg font-semibold hover:bg-blue-700 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
              >
                추천받기
              </button>
            )}
          </div>
        </Card>

        {/* ── 결과 ── */}
        {result && (
          <>
            <RecommendGrid items={result.items} onSelect={() => navigate('/order')} />
            <BrandSection brands={brands} onSelect={() => navigate('/order')} />
            <OutfitSection outfits={result.outfits} budget={budget} />
            <p className="text-center text-xs text-gray-400 mt-8 mb-2">{result.disclaimer}</p>
          </>
        )}
        {!result && !loadingData && (
          <p className="text-center text-sm text-gray-400 py-10">
            조건을 정하고 [추천받기]를 누르면 결과가 나타납니다.
          </p>
        )}
        {!result && (
          <p className="text-center text-[11px] text-gray-300">{DISCLAIMER}</p>
        )}
      </div>
    </div>
  );
};

/* ─────────────────────────────────────────
   추천 상품 그리드
───────────────────────────────────────── */
const RecommendGrid: React.FC<{ items: ScoredProduct[]; onSelect: () => void }> = ({ items, onSelect }) => {
  const top = items.slice(0, 9);
  return (
    <div className="mb-8">
      <h2 className="text-xl font-bold text-gray-900 mb-3">추천 상품</h2>
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
        {top.map((it) => (
          <button
            key={it.product.id}
            onClick={onSelect}
            className="text-left bg-white rounded-lg shadow hover:shadow-md transition-shadow p-4 flex flex-col"
          >
            <div className="flex items-start justify-between mb-2">
              <span className="inline-block px-2 py-0.5 rounded text-xs font-semibold bg-gray-100 text-gray-600">
                {CATEGORY_LABELS[it.category]}
              </span>
              <span className={`inline-block px-2 py-0.5 rounded-full text-xs font-bold ${scoreBadgeClass(it.score)}`}>
                {it.score}점
              </span>
            </div>
            <p className="text-sm font-semibold text-gray-900 mb-1">{it.product.name}</p>
            <div className="mb-2">
              {it.couponDiscount > 0 ? (
                <>
                  <span className="text-xs text-gray-400 line-through mr-1">{fmt(it.product.price)}</span>
                  <span className="text-sm font-bold text-blue-700">{fmt(it.effectivePrice)}</span>
                  <span className="ml-1 text-xs text-green-600 font-medium">쿠폰가</span>
                </>
              ) : (
                <span className="text-sm font-bold text-gray-900">{fmt(it.product.price)}</span>
              )}
            </div>

            {/* honest-stylist 4단 구조 */}
            <div className="mt-auto space-y-1.5 text-xs">
              <p className="font-semibold text-gray-800">결론 · {it.verdict.verdict}</p>
              <ul className="list-disc list-inside text-gray-500 space-y-0.5">
                {it.verdict.evidence.map((e, i) => (
                  <li key={i}>{e}</li>
                ))}
              </ul>
              <p className="text-amber-700">한계 · {it.verdict.counter}</p>
              <p className="text-blue-600">다음 · {it.verdict.nextStep}</p>
            </div>
          </button>
        ))}
      </div>
    </div>
  );
};

/* ─────────────────────────────────────────
   브랜드 추천 (히스토리 + 영적 마케팅)
───────────────────────────────────────── */
const BrandSection: React.FC<{ brands: BrandReco[]; onSelect: () => void }> = ({ brands, onSelect }) => {
  if (brands.length === 0) return null;
  return (
    <div className="mb-8">
      <h2 className="text-xl font-bold text-gray-900 mb-1">이 브랜드의 정신까지 — 브랜드 추천</h2>
      <p className="text-sm text-gray-500 mb-3">
        옷은 브랜드의 세계관을 입는 일 — 추천 상품의 브랜드 히스토리와 정신을 함께 보여드립니다.
      </p>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {brands.map((b) => (
          <div key={b.brand.key} className="bg-white rounded-lg shadow p-5 flex flex-col">
            {/* 브랜드명 + 매칭 점수 */}
            <div className="flex items-center justify-between mb-3">
              <span className="text-lg font-bold text-gray-900">{b.brand.name}</span>
              <span className="inline-block px-2 py-0.5 rounded-full text-xs font-bold bg-indigo-100 text-indigo-800">
                매칭 {b.score}점
              </span>
            </div>

            {/* 히스토리 타임라인 */}
            <div className="mb-4">
              <p className="text-xs font-semibold text-gray-400 mb-2">HERITAGE</p>
              <ol className="relative border-l border-gray-200 ml-1.5 space-y-3">
                <li className="ml-4">
                  <span className="absolute -left-1.5 w-3 h-3 rounded-full bg-indigo-400 border-2 border-white" />
                  <p className="text-sm font-semibold text-gray-800">
                    {b.brand.heritage.founded} · {b.brand.heritage.origin}
                  </p>
                </li>
                {b.brand.heritage.milestones.map((m, i) => (
                  <li key={i} className="ml-4">
                    <span className="absolute -left-1.5 w-3 h-3 rounded-full bg-gray-300 border-2 border-white" />
                    <p className="text-xs text-gray-500 leading-relaxed">{m}</p>
                  </li>
                ))}
              </ol>
            </div>

            {/* 영적 마케팅 — 파는 '의미' */}
            <div className="mb-3">
              <p className="text-xs font-semibold text-gray-400 mb-1.5">SPIRIT · 브랜드가 파는 의미</p>
              <blockquote className="border-l-4 border-indigo-400 bg-indigo-50 pl-3 py-2 rounded-r">
                <p className="text-sm font-semibold text-indigo-900">“{b.brand.spirit.essence}”</p>
                <p className="text-xs text-gray-600 mt-1.5 leading-relaxed">{b.brand.spirit.narrative}</p>
              </blockquote>
            </div>

            {/* 가치 태그 칩 */}
            <div className="flex flex-wrap gap-1.5 mb-4">
              {b.brand.spirit.tags.map((t) => {
                const matched = b.matchedTags.includes(t);
                return (
                  <span
                    key={t}
                    className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                      matched ? 'bg-indigo-600 text-white' : 'bg-gray-100 text-gray-500'
                    }`}
                  >
                    #{t}
                  </span>
                );
              })}
            </div>

            {/* 이 정신이 담긴 대표 추천 상품 */}
            <button
              onClick={onSelect}
              className="mt-auto text-left w-full border border-gray-200 rounded-lg p-3 hover:border-indigo-400 hover:bg-indigo-50 transition-colors"
            >
              <p className="text-[11px] text-gray-400 mb-1">이 정신이 담긴 추천 상품</p>
              <div className="flex items-center justify-between">
                <span className="text-sm font-medium text-gray-800">{b.representative.product.name}</span>
                <span className="text-xs font-bold text-gray-900 whitespace-nowrap ml-2">
                  {b.representative.score}점 · {fmt(b.representative.effectivePrice)}
                </span>
              </div>
            </button>
          </div>
        ))}
      </div>
    </div>
  );
};

/* ─────────────────────────────────────────
   3-코디 제안
───────────────────────────────────────── */
const OutfitSection: React.FC<{ outfits: RecommendResult['outfits']; budget: number }> = ({ outfits, budget }) => {
  if (outfits.length === 0) {
    return (
      <Card title="3-코디 제안">
        <p className="text-sm text-gray-400">
          상의·하의가 모두 있어야 코디를 구성할 수 있습니다. 조건을 넓혀 다시 시도해보세요.
        </p>
      </Card>
    );
  }
  return (
    <div className="mb-8">
      <h2 className="text-xl font-bold text-gray-900 mb-3">3-코디 제안</h2>
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {outfits.map((set, idx) => {
          const pct = Math.min(100, Math.round(set.budgetRatio * 100));
          return (
            <div key={idx} className="bg-white rounded-lg shadow p-4">
              <div className="flex items-center justify-between mb-2">
                <span className="font-semibold text-gray-900">{set.label}</span>
                <span
                  className={`text-xs font-semibold px-2 py-0.5 rounded ${
                    set.withinBudget ? 'bg-green-100 text-green-700' : 'bg-red-100 text-red-700'
                  }`}
                >
                  {set.withinBudget ? '예산 내' : '예산 초과'}
                </span>
              </div>
              <ul className="space-y-1.5 mb-3">
                {set.items.map((it) => (
                  <li key={it.product.id} className="flex justify-between text-sm">
                    <span className="text-gray-700">
                      <span className="text-gray-400 mr-1">[{CATEGORY_LABELS[it.category]}]</span>
                      {it.product.name}
                    </span>
                    <span className="text-gray-900 font-medium whitespace-nowrap ml-2">{fmt(it.effectivePrice)}</span>
                  </li>
                ))}
              </ul>
              <div className="border-t pt-2">
                <div className="flex justify-between text-sm font-semibold mb-1">
                  <span>합계</span>
                  <span className={set.withinBudget ? 'text-gray-900' : 'text-red-600'}>{fmt(set.totalPrice)}</span>
                </div>
                <div className="w-full bg-gray-100 rounded-full h-2 overflow-hidden">
                  <div
                    className={`h-2 rounded-full ${set.withinBudget ? 'bg-blue-500' : 'bg-red-500'}`}
                    style={{ width: `${pct}%` }}
                  />
                </div>
                <p className="text-[11px] text-gray-400 mt-1 text-right">
                  예산 {fmt(budget)} 대비 {pct}%
                </p>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default RecommendPage;
