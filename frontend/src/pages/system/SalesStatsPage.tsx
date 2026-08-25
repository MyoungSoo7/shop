import React, { useCallback, useEffect, useState } from 'react';
import {
  salesStatsApi,
  toIsoDate,
  type CategoryBreakdown,
  type ProductRanking,
  type SalesQuery,
} from '@/api/salesStats';
import { saveBlob } from '@/api/auditLog';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 판매 통계 — <b>무엇이</b> 팔렸나.
 *
 * <p>매출 화면과 숫자가 다르다. 저쪽은 결제 원장에서 "얼마가 들어왔나"를 세고, 여기는 주문
 * 라인에서 "무엇이 몇 개 팔렸나"를 센다. 부분 환불·수납 시차·미결제 주문이 각각 한쪽에만
 * 반영되므로 두 값은 <b>일부러</b> 어긋난다 — 화면이 그 사실을 먼저 말한다. 안 그러면 둘이
 * 다른 것을 버그로 보고, 맞추려다 회계 숫자를 망친다.
 *
 * <p><b>랭킹은 본래 잘라낸 목록이다.</b> 이 화면의 유일한 진짜 위험은 표에 담긴 행의 합계를
 * "전체 매출"로 읽는 것이다. 그래서 표 위에 <b>전 범위 합계</b>를 먼저 놓고, 표가 그중 얼마를
 * 덮는지 함께 적는다.
 *
 * <p>카테고리 분포는 자르지 않는다. 대표 분류가 없는 상품도 '미분류' 한 줄로 남는다 — 빼면
 * 분포의 합이 총액에 못 미치는 것을 볼 사람이 없다.
 */

const LIMITS = [10, 20, 50, 100];

const fmtNumber = (v: number) => new Intl.NumberFormat('ko-KR').format(v);
const fmtMoney = (v: number) =>
  new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

/** 표가 전 범위의 몇 %를 덮는가. 전체가 0 이면 비율 자체가 뜻이 없다. */
const coverage = (rows: number, total: number) =>
  total <= 0 ? null : Math.round((rows / total) * 1000) / 10;

type Tab = 'products' | 'categories';

const SalesStatsPage: React.FC = () => {
  const [tab, setTab] = useState<Tab>('products');
  const [from, setFrom] = useState(() => {
    const now = new Date();
    return toIsoDate(new Date(now.getFullYear(), now.getMonth(), now.getDate() - 29));
  });
  const [to, setTo] = useState(() => toIsoDate(new Date()));
  const [limit, setLimit] = useState(20);

  const [ranking, setRanking] = useState<ProductRanking | null>(null);
  const [breakdown, setBreakdown] = useState<CategoryBreakdown | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const query = useCallback((): SalesQuery => ({ from, to, limit }), [from, to, limit]);

  const load = useCallback(async () => {
    setError(null);
    try {
      if (tab === 'products') {
        setRanking(await salesStatsApi.topProducts(query()));
      } else {
        setBreakdown(await salesStatsApi.byCategory({ from, to }));
      }
    } catch (err) {
      // 빈 표를 그리면 조회 실패가 "그 기간에 아무것도 안 팔렸다"로 위장한다.
      if (tab === 'products') setRanking(null); else setBreakdown(null);
      setError(apiErrorMessage(err, '판매 통계를 불러오지 못했습니다.'));
    }
  }, [tab, query, from, to]);

  useEffect(() => { void load(); }, [load]);

  const download = async () => {
    setError(null);
    try {
      const result = tab === 'products'
        ? await salesStatsApi.exportProducts(query())
        : await salesStatsApi.exportCategories({ from, to });
      saveBlob(result.blob, result.fileName);
      setNotice(result.truncated
        ? `내려받았습니다 — 상위 ${result.limit ?? limit}개만 담겼습니다. 전 범위 순매출은 ${
          result.netAmount === null ? '헤더에 없음' : fmtMoney(result.netAmount)}입니다.`
        : '내려받았습니다 — 잘라낸 행이 없습니다.');
    } catch (err) {
      setError(apiErrorMessage(err, 'CSV 를 내려받지 못했습니다.'));
    }
  };

  const rowsNet = tab === 'products'
    ? (ranking?.rows ?? []).reduce((sum, r) => sum + r.netAmount, 0)
    : (breakdown?.rows ?? []).reduce((sum, r) => sum + r.netAmount, 0);
  const total = tab === 'products' ? ranking?.total : breakdown?.total;
  const covered = total ? coverage(rowsNet, total.netAmount) : null;

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">판매 통계</h1>
          {/* 매출 화면과 다른 숫자라는 것을 맨 앞에 둔다 — 나중에 말하면 이미 오해한 뒤다. */}
          <p className="text-sm text-gray-500 mt-1">
            주문 라인 기준입니다. 결제 원장 기준인 <b>매출</b> 화면과 숫자가 다른 것이 정상이며,
            회계 숫자로 쓰면 안 됩니다.
          </p>
        </div>

        <div className="flex gap-2" role="tablist">
          <button type="button" role="tab" aria-selected={tab === 'products'}
            onClick={() => setTab('products')}
            className={`rounded px-3 py-1.5 text-sm font-semibold ${
              tab === 'products' ? 'bg-gray-900 text-white' : 'border border-gray-300 bg-white text-gray-700'}`}>
            상품 랭킹
          </button>
          <button type="button" role="tab" aria-selected={tab === 'categories'}
            onClick={() => setTab('categories')}
            className={`rounded px-3 py-1.5 text-sm font-semibold ${
              tab === 'categories' ? 'bg-gray-900 text-white' : 'border border-gray-300 bg-white text-gray-700'}`}>
            카테고리 분포
          </button>
        </div>

        <div className="flex flex-wrap items-end gap-3 rounded bg-white p-4 shadow-sm">
          <label className="text-sm">
            <span className="block text-gray-600">시작일</span>
            <input type="date" value={from} aria-label="시작일"
              onChange={(e) => setFrom(e.target.value)}
              className="mt-1 rounded border border-gray-300 px-2 py-1" />
          </label>
          <label className="text-sm">
            <span className="block text-gray-600">종료일</span>
            <input type="date" value={to} aria-label="종료일"
              onChange={(e) => setTo(e.target.value)}
              className="mt-1 rounded border border-gray-300 px-2 py-1" />
          </label>
          {tab === 'products' && (
            <label className="text-sm">
              <span className="block text-gray-600">상위</span>
              <select value={limit} aria-label="상위 개수"
                onChange={(e) => setLimit(Number(e.target.value))}
                className="mt-1 rounded border border-gray-300 px-2 py-1">
                {LIMITS.map((n) => <option key={n} value={n}>{n}개</option>)}
              </select>
            </label>
          )}
          <button type="button" onClick={() => void load()}
            className="rounded bg-gray-900 px-3 py-2 text-sm font-semibold text-white">조회</button>
          <button type="button" onClick={() => void download()}
            className="rounded border border-gray-300 bg-white px-3 py-2 text-sm font-semibold text-gray-700">
            CSV
          </button>
        </div>

        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
        {notice && <p className="text-sm text-green-700" data-testid="sales-notice">{notice}</p>}

        {total && (
          // 표 위에 전 범위 합계를 먼저 놓는다. 표 아래에 두면 행 합계를 먼저 읽는다.
          <div className="rounded bg-white p-4 shadow-sm" data-testid="sales-total">
            <p className="text-sm text-gray-600">전 범위 합계 (표에 담기지 않은 것 포함)</p>
            <p className="text-xl font-bold text-gray-900">
              {fmtMoney(total.netAmount)} · {fmtNumber(total.quantity)}개 · 주문 {fmtNumber(total.orderCount)}건
            </p>
            {tab === 'products' && covered !== null && (
              <p className="mt-1 text-xs text-gray-500" data-testid="sales-coverage">
                아래 표는 이 중 {covered}% 를 덮습니다 — 표의 합계는 전체 매출이 아닙니다.
              </p>
            )}
          </div>
        )}

        {tab === 'products' ? (
          ranking === null ? (
            !error && <p className="text-sm text-gray-500">불러오는 중…</p>
          ) : ranking.rows.length === 0 ? (
            <p className="text-sm text-gray-600" data-testid="sales-empty">
              이 기간에 팔린 상품이 없습니다.
            </p>
          ) : (
            <table className="w-full text-sm" data-testid="product-table">
              <thead className="text-left text-gray-500">
                <tr>
                  <th className="py-2">순위</th><th>상품</th>
                  <th className="text-right">수량</th><th className="text-right">순매출</th>
                  <th className="text-right">주문수</th>
                </tr>
              </thead>
              <tbody>
                {ranking.rows.map((row, index) => (
                  <tr key={row.productId} className="border-t" data-testid={`product-row-${row.productId}`}>
                    {/* 순위는 행의 속성이 아니라 위치다 — 서버가 준 순서를 그대로 번호로 쓴다. */}
                    <td className="py-2">{index + 1}</td>
                    <td className="text-gray-900">{row.productName}</td>
                    <td className="text-right">{fmtNumber(row.quantity)}</td>
                    <td className="text-right">{fmtMoney(row.netAmount)}</td>
                    <td className="text-right">{fmtNumber(row.orderCount)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )
        ) : breakdown === null ? (
          !error && <p className="text-sm text-gray-500">불러오는 중…</p>
        ) : breakdown.rows.length === 0 ? (
          <p className="text-sm text-gray-600" data-testid="sales-empty">
            이 기간에 팔린 상품이 없습니다.
          </p>
        ) : (
          <table className="w-full text-sm" data-testid="category-table">
            <thead className="text-left text-gray-500">
              <tr>
                <th className="py-2">카테고리</th><th>경로</th>
                <th className="text-right">수량</th><th className="text-right">순매출</th>
                <th className="text-right">주문수</th>
              </tr>
            </thead>
            <tbody>
              {breakdown.rows.map((row) => (
                <tr key={row.categoryId ?? 'unclassified'} className="border-t"
                  data-testid={`category-row-${row.categoryId ?? 'unclassified'}`}>
                  <td className="py-2 text-gray-900">
                    {/* 대표 분류가 없는 상품을 빼면 분포의 합이 총액에 못 미치는 것을 볼 사람이 없다. */}
                    {row.categoryId === null ? '미분류' : row.categoryName}
                  </td>
                  <td className="text-xs text-gray-500">{row.pathSlug ?? '-'}</td>
                  <td className="text-right">{fmtNumber(row.quantity)}</td>
                  <td className="text-right">{fmtMoney(row.netAmount)}</td>
                  <td className="text-right">{fmtNumber(row.orderCount)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  );
};

export default SalesStatsPage;
