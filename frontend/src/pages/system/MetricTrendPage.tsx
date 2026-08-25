import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  metricTrendApi,
  toIsoDate,
  daysAgo,
  type MetricTrendResponse,
  type TrendTotal,
} from '@/api/metricTrend';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 지표 추이 — 대시보드 카드에 <b>비교 대상</b>을 붙인다.
 *
 * <p>"오늘 한눈에"는 오늘의 숫자만 준다. 카드 하나만 보면 "주문 12건"이 많은지 적은지 알 수
 * 없다 — 비교할 어제가 없기 때문이다. 이 화면은 같은 지표를 날짜 축으로 편다.
 *
 * <p><b>0 인 날을 빈칸으로 두지 않는다.</b> 서버가 없는 날을 0 으로 채워 보내고 화면도 그대로
 * 그린다. 구멍을 두면 선이 옆 날짜와 이어져 <b>없던 기울기</b>가 생기기 때문이다. 대신 합계가
 * 불완전한 지표에는 "일부 미상"을 붙인다 — 0 과 미상은 다른 값이다.
 *
 * <p><b>기간 안에 이벤트가 하나도 없으면 {@code asOf} 가 null 이다.</b> 그때 조회 시각 같은 다른
 * 시각을 대신 찍으면, 집계가 멈춘 것을 "방금 갱신됨"으로 위장하게 된다 — 그냥 "아직 없음"이라고 쓴다.
 */

const fmtNumber = (v: number) => new Intl.NumberFormat('ko-KR').format(v);

const fmtMoney = (v: number | null) =>
  v === null ? '-' : new Intl.NumberFormat('ko-KR', { style: 'currency', currency: 'KRW' }).format(v);

const fmtInstant = (s: string | null) =>
  s === null ? '아직 없음' : new Date(s).toLocaleString('ko-KR', { dateStyle: 'medium', timeStyle: 'short' });

/** 막대 하나의 높이(%) — 그 지표의 최댓값을 100 으로 본다. 지표끼리 높이를 비교하지 않는다. */
const barHeight = (value: number, max: number) => (max <= 0 ? 0 : Math.max((value / max) * 100, 2));

const MetricTrendPage: React.FC = () => {
  const [from, setFrom] = useState(() => toIsoDate(daysAgo(29)));
  const [to, setTo] = useState(() => toIsoDate(new Date()));
  /** 빈 집합 = 전 지표. 서버 기본값과 같은 의미라 화면이 목록을 알 필요가 없다. */
  const [selected, setSelected] = useState<string[]>([]);

  const [data, setData] = useState<MetricTrendResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (metrics: string[]) => {
    setError(null);
    try {
      setData(await metricTrendApi.trend({ from, to, metrics }));
    } catch (err) {
      // 빈 그래프를 그리면 조회 실패가 "그 기간에 아무 일도 없었다"로 위장한다.
      setData(null);
      setError(apiErrorMessage(err, '지표 추이를 불러오지 못했습니다.'));
    }
  }, [from, to]);

  useEffect(() => { void load(selected); }, [load, selected]);

  /** 지표별 날짜 계열. 서버는 한 배열로 주고, 그리는 단위는 지표다. */
  const seriesByMetric = useMemo(() => {
    const grouped = new Map<string, { date: string; count: number; amount: number | null }[]>();
    for (const point of data?.series ?? []) {
      const list = grouped.get(point.metric) ?? [];
      list.push({ date: point.date, count: point.count, amount: point.amount });
      grouped.set(point.metric, list);
    }
    return grouped;
  }, [data]);

  const toggle = (metric: string) => {
    setSelected((prev) => prev.includes(metric)
      ? prev.filter((m) => m !== metric)
      : [...prev, metric]);
  };

  // ?? [] 를 그대로 두면 렌더마다 새 배열이라 아래 useEffect 가 매번 다시 돈다.
  const totals: TrendTotal[] = useMemo(() => data?.totals ?? [], [data]);
  /** 필터 후보는 서버가 준 목록에서만 만든다 — 화면에 지표 매핑표를 두지 않는다. */
  const [known, setKnown] = useState<TrendTotal[]>([]);
  useEffect(() => {
    if (selected.length === 0 && totals.length > 0) {
      setKnown(totals);
    }
  }, [selected.length, totals]);

  return (
    <div className="min-h-screen bg-gray-50 py-8 px-4 sm:px-6 lg:px-8">
      <div className="max-w-6xl mx-auto space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">지표 추이</h1>
          <p className="text-sm text-gray-500 mt-1">
            대시보드 카드의 숫자를 날짜 축으로 편 것입니다. 오늘 값 하나만으로는 많은지 적은지
            알 수 없습니다.
          </p>
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
          <button type="button" onClick={() => void load(selected)}
            className="rounded bg-gray-900 px-3 py-2 text-sm font-semibold text-white">조회</button>
          {selected.length > 0 && (
            <button type="button" onClick={() => setSelected([])}
              className="rounded border border-gray-300 bg-white px-3 py-2 text-sm text-gray-700">
              전 지표 보기
            </button>
          )}
        </div>

        {known.length > 0 && (
          <div className="flex flex-wrap gap-2" data-testid="metric-filters">
            {known.map((total) => (
              <button key={total.metric} type="button" onClick={() => toggle(total.metric)}
                aria-pressed={selected.includes(total.metric)}
                className={`rounded px-3 py-1.5 text-sm font-semibold ${
                  selected.includes(total.metric)
                    ? 'bg-gray-900 text-white'
                    : 'border border-gray-300 bg-white text-gray-700'}`}>
                {total.label}
              </button>
            ))}
          </div>
        )}

        {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

        {data === null ? (
          !error && <p className="text-sm text-gray-500">불러오는 중…</p>
        ) : (
          <>
            <p className="text-xs text-gray-500" data-testid="trend-as-of">
              {data.from} ~ {data.to} ({data.zone}) · 마지막 반영 {fmtInstant(data.asOf)}
            </p>

            {totals.length === 0 ? (
              <p className="text-sm text-gray-600" data-testid="trend-empty">
                이 기간에 집계된 지표가 없습니다.
              </p>
            ) : (
              <div className="space-y-6">
                {totals.map((total) => {
                  const series = seriesByMetric.get(total.metric) ?? [];
                  const max = series.reduce((m, p) => Math.max(m, p.count), 0);
                  return (
                    <section key={total.metric} className="rounded bg-white p-4 shadow-sm"
                      data-testid={`trend-${total.metric}`}>
                      <div className="flex flex-wrap items-baseline justify-between gap-2">
                        <h2 className="text-lg font-semibold text-gray-900">{total.label}</h2>
                        <p className="text-sm text-gray-700">
                          {fmtNumber(total.count)}건
                          {total.hasAmount && <> · {fmtMoney(total.amount)}</>}
                          {/* 합계에 빠진 건이 있으면 반드시 말한다 — 없으면 이 값은 하한이다. */}
                          {!total.amountComplete && (
                            <span className="ml-2 rounded bg-amber-100 px-2 py-0.5 text-xs text-amber-800"
                              data-testid={`incomplete-${total.metric}`}>
                              금액 일부 미상 {fmtNumber(total.amountUnknownCount)}건 — 하한값
                            </span>
                          )}
                        </p>
                      </div>

                      <div className="mt-3 flex h-24 items-end gap-px" role="img"
                        aria-label={`${total.label} 일별 건수`}>
                        {series.map((point) => (
                          <div key={point.date} title={`${point.date} · ${fmtNumber(point.count)}건`}
                            className="flex-1 bg-gray-800"
                            style={{ height: `${barHeight(point.count, max)}%` }} />
                        ))}
                      </div>
                      <div className="mt-1 flex justify-between text-xs text-gray-500">
                        <span>{series[0]?.date ?? ''}</span>
                        <span>최대 {fmtNumber(max)}건</span>
                        <span>{series[series.length - 1]?.date ?? ''}</span>
                      </div>
                    </section>
                  );
                })}
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
};

export default MetricTrendPage;
