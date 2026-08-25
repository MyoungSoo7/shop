import api from './axios';

/**
 * 지표 추이 — operation-service {@code MetricTrendController}.
 *
 * <p><b>"오늘 한눈에"({@code /api/ops/dashboard/today})와 짝이다.</b> 그쪽은 오늘 하루의 숫자를
 * 카드로 준다. 카드 하나만 보면 "주문 12건"이 많은 건지 적은 건지 알 수 없다 — 비교할 어제가
 * 없기 때문이다. 이 API 는 같은 지표를 <b>날짜 축</b>으로 펴 준다.
 *
 * <p><b>없는 날은 서버가 0 으로 채운다.</b> 매출 API({@code /admin/revenue})와 반대 결정이다.
 * 저쪽은 "집계가 안 돌았다"와 "그날 장사가 없었다"를 구분해야 해서 행을 비우지만, 여기는
 * 추이선을 그리는 것이 목적이라 구멍이 있으면 선이 끊기거나 옆 날짜와 이어져 <b>없던 기울기가
 * 생긴다</b>. 대신 {@code amountComplete} 로 "합계에 빠진 건이 있다"를 따로 알린다.
 */

/** 하루치 한 지표. */
export interface TrendPoint {
  /** yyyy-MM-dd */
  date: string;
  /** 서버 enum 이름. 화면 분기는 이 값으로 한다(라벨은 totals 가 준다). */
  metric: string;
  count: number;
  /** 금액 없는 지표(회원가입 등)는 null. "0원"을 찍지 않기 위한 구분이다. */
  amount: number | null;
  /** false 면 그날 합계에 금액을 알 수 없는 건이 섞여 있다. */
  amountComplete: boolean;
  amountUnknownCount: number;
}

/** 기간 전체의 지표별 합계 + 한글 라벨(서버가 정본). */
export interface TrendTotal {
  metric: string;
  label: string;
  count: number;
  amount: number | null;
  hasAmount: boolean;
  amountComplete: boolean;
  amountUnknownCount: number;
}

export interface MetricTrendResponse {
  from: string;
  to: string;
  /** 날짜를 자른 타임존. 화면이 "하루"의 정의를 말할 수 있어야 한다. */
  zone: string;
  /**
   * 집계에 반영된 마지막 이벤트 시각. 기간 안에 한 건도 없으면 null — 그때 화면은 시각 대신
   * "아직 없음"을 그린다. 없는 기간에 엉뚱한 시각(조회 시각 등)을 찍는 것이 가장 나쁘다.
   */
  asOf: string | null;
  /** 실제로 조회된 지표 목록 — 요청한 것과 다를 수 있다(서버가 정본). */
  metrics: string[];
  series: TrendPoint[];
  totals: TrendTotal[];
}

/** yyyy-MM-dd — 서버는 ISO DATE 만 받는다(시각을 붙이면 400). */
export const toIsoDate = (d: Date): string =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

/** 오늘로부터 N일 전. 기본 조회창(30일)을 화면과 테스트가 같은 식으로 만든다. */
export const daysAgo = (days: number, now: Date = new Date()): Date =>
  new Date(now.getFullYear(), now.getMonth(), now.getDate() - days);

export interface TrendQuery {
  from: string;
  /** 종료일(<b>포함</b>). 서버 상한 366일. */
  to: string;
  /** 비우면 서버가 전 지표를 준다. */
  metrics?: string[];
}

export const metricTrendApi = {
  /**
   * 기간 추이 (ADMIN — operation-service 보안 체인이 {@code /api/ops/**} 를 막는다).
   *
   * <p>지표 목록은 <b>쉼표로 이어</b> 보낸다. axios 기본 직렬화는 배열을 {@code metric[]=A&metric[]=B}
   * 로 펴는데 스프링의 {@code @RequestParam List<String> metric} 은 그 이름을 못 읽어 조용히
   * 전 지표 조회가 된다 — 필터가 안 걸린 것을 화면에서는 알아챌 수 없다.
   */
  trend: async (query: TrendQuery): Promise<MetricTrendResponse> =>
    (
      await api.get<MetricTrendResponse>('/api/ops/dashboard/trend', {
        params: {
          from: query.from,
          to: query.to,
          ...(query.metrics && query.metrics.length > 0
            ? { metric: query.metrics.join(',') }
            : {}),
        },
      })
    ).data,
};
