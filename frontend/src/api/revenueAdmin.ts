import api from './axios';

/**
 * 기간 매출 조회 — order-service {@code AdminRevenueController}.
 *
 * <p><b>왜 이 모듈이 생겼나.</b> 대시보드의 "총 매출"은 {@code /orders/admin/summary} 가 준
 * 상태별 합계 중 <b>현재 상태가 PAID 인 주문</b>의 주문금액 합이었다. 주문이 발송(IN_TRANSIT)
 * 되거나 배송 완료(DELIVERED)되면 PAID 가 아니게 되므로 그 주문은 매출에서 빠진다 —
 * 장사가 굴러갈수록 줄어드는 숫자다. 환불도 차감되는 게 아니라 다른 상태 칸으로 옮겨갈 뿐이라
 * 그냥 사라진다. 가장 나쁜 점은 그 값이 늘 <b>그럴듯하다</b>는 것이다.
 *
 * <p>여기서 오는 숫자는 주문의 현재 상태가 아니라 <b>결제 원장</b>에 달린다 — 실제 수납 시각과
 * 환불 완료 시각. 그래서 기간이 반드시 있어야 한다("전 기간 매출"은 이 API 로 못 부른다).
 */

export interface DailyRevenue {
  /** yyyy-MM-dd */
  date: string;
  capturedCount: number;
  capturedAmount: number;
  refundCount: number;
  refundedAmount: number;
  /** 수납 − 환불. 환불만 있던 날은 <b>음수</b>다. */
  netAmount: number;
}

export interface TenderRevenue {
  /** CARD · KAKAO_PAY · POINT · GIFT_CARD … */
  tenderType: string;
  /**
   * 외부 PG 로 실제 돈이 들어왔는가. POINT·GIFT_CARD 는 내부 잔액 차감이라 이 기간에 새로
   * 들어온 현금이 아니다 — 상품권은 팔릴 때 이미 한 번 수납됐다. 한 줄에 합치면 이중으로 센다.
   */
  usesExternalPg: boolean;
  count: number;
  amount: number;
}

export interface RevenueReport {
  from: string;
  to: string;
  capturedAmount: number;
  refundedAmount: number;
  /** 서버가 계산해 내려보낸다 — 화면마다 빼기를 다시 하면 어느 화면은 환불을 안 뺀다. */
  netAmount: number;
  /** 결제수단을 특정하지 못한 수납액(분할결제 도입 전 결제는 tender 행이 없다). */
  unattributedAmount: number;
  /**
   * 수단별 합계가 총 수납액을 전부 설명하는가. {@code false} 면 화면은 반드시 "수단 미상"을
   * 함께 보여야 한다 — 구성 비율만 그리면 합이 총액에 못 미치는 것을 볼 사람이 없다.
   */
  tenderBreakdownComplete: boolean;
  /**
   * 일자별 추이. <b>수납도 환불도 없던 날은 행이 없다</b> — 서버가 0 을 채우면 "집계가 안 돌았다"와
   * "그날 장사가 없었다"가 같은 모양이 된다. 0 을 그리는 것은 화면의 몫이다.
   */
  daily: DailyRevenue[];
  byTender: TenderRevenue[];
}

/** yyyy-MM-dd — 서버는 ISO DATE 만 받는다(시각을 붙이면 400). */
export const toIsoDate = (d: Date): string =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

export const revenueAdminApi = {
  /**
   * 기간 매출 (ADMIN·MANAGER — 서버가 게이트한다).
   *
   * @param from 시작일(포함) yyyy-MM-dd
   * @param to   종료일(<b>포함</b>) yyyy-MM-dd. 최대 366일
   */
  report: async (from: string, to: string): Promise<RevenueReport> =>
    (await api.get<RevenueReport>('/admin/revenue', { params: { from, to } })).data,
};
