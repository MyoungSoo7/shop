import api from './axios';

/**
 * 운영 대시보드 "오늘 한눈에" API.
 *
 * <p>이 파일이 존재하는 이유는 관리자 개요 탭이 숫자를 만들던 방식 때문이다 — 주문 전건·회원
 * 전건·상품 전건·쿠폰 전건을 브라우저로 내려받아 `reduce()` 했다. 데이터가 늘면 화면이 느려지다
 * 죽고, 카드 몇 개를 위해 전 회원의 이메일이 브라우저로 내려오며, 얻은 값은 '오늘'이 아니라
 * 전 기간 누계였다. 서버가 이미 하루치를 집계해 두므로 화면은 <b>카드에 찍히는 숫자만</b> 받는다.
 *
 * <p>그렇다고 기존 집계를 지우지는 않았다. 다른 탭(주문·회원·상품·쿠폰 목록)이 같은 데이터를
 * 쓰고 있어 한 번에 걷어내면 화면이 통째로 빈다. 대신 그쪽 카드에는 "전체 기간"을 붙여 둘을
 * 구분한다 — 같은 화면에 기간이 다른 숫자가 라벨 없이 나란히 있으면 반드시 오독된다.
 */

/** 카드 한 장. */
export interface MetricCard {
  /** 서버 enum 이름. 화면 분기는 이 값으로 한다(라벨은 번역돼 바뀔 수 있다). */
  key: string;
  /** 한글 표기 — 서버가 정본. 화면에 매핑 테이블을 두지 않는다. */
  label: string;
  count: number;
  /** `hasAmount` 가 false 면 null. 금액 없는 지표에 "0원"을 찍지 않기 위한 구분이다. */
  amount: string | null;
  hasAmount: boolean;
  /** false 면 합계에 빠진 건이 있다는 뜻 — 화면이 "일부 미상"을 반드시 표시해야 한다. */
  amountComplete: boolean;
  amountUnknownCount: number;
}

export interface TodayOverview {
  /** 집계 기준 날짜(YYYY-MM-DD). */
  date: string;
  /** 그 날짜를 자른 타임존. 화면이 "오늘"의 정의를 말할 수 있어야 한다. */
  zone: string;
  /**
   * 집계에 반영된 마지막 이벤트 시각. 이벤트로 채워지는 화면은 항상 조금 늦으므로 이 값을
   * 같이 보여 준다. 오늘 이벤트가 하나도 없으면 null — 이때 다른 시각을 대신 보여 주면 안 된다.
   */
  asOf: string | null;
  metrics: MetricCard[];
  openIncidents: number;
  failedDispatches: number;
}

export const opsDashboardApi = {
  /** date 를 주면 그 날짜, 없으면 서버 기준 오늘. */
  today: async (date?: string) =>
    (
      await api.get<TodayOverview>('/api/ops/dashboard/today', {
        params: date ? { date } : undefined,
      })
    ).data,
};
