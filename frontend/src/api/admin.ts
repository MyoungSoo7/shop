import api from './axios';
import { OrderResponse } from '@/types';

export interface AdminUserResponse {
  id: number;
  email: string;
  role: string;
  createdAt: string;
}

/** 관리자 주문 조회 조건. 비우면 전 기간·전 상태. */
export interface AdminOrderQuery {
  /** 상태 정확일치(OR). 여러 개면 반복 파라미터로 나간다. */
  status?: string[];
  /** 주문일시 시작(포함) — ISO date-time */
  from?: string;
  /** 주문일시 종료(미포함) — ISO date-time */
  to?: string;
  page?: number;
  size?: number;
}

export interface AdminOrderPage {
  content: OrderResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AdminOrderStatusCount {
  status: string;
  count: number;
  /** BigDecimal 이 문자열로 온다 — 숫자로 받으면 큰 값에서 정밀도가 샌다. */
  amountSum: string | null;
}

export interface AdminOrderSummary {
  totalCount: number;
  /** 상태를 가리지 않은 금액 총합. "매출"이 아니다 — 취소·환불도 포함한다. */
  totalAmount: string | null;
  statuses: AdminOrderStatusCount[];
}

/**
 * 목록 파라미터. 빈 값은 아예 보내지 않는다 — `status=` 처럼 빈 문자열을 실어 보내면
 * 서버에서 "빈 상태와 일치하는 주문"으로 읽힐 여지가 생긴다.
 */
const toParams = (query: AdminOrderQuery = {}): Record<string, unknown> => {
  const params: Record<string, unknown> = {};
  if (query.status && query.status.length > 0) params.status = query.status;
  if (query.from) params.from = query.from;
  if (query.to) params.to = query.to;
  if (query.page !== undefined) params.page = query.page;
  if (query.size !== undefined) params.size = query.size;
  return params;
};

export const adminApi = {
  /**
   * GET /orders/admin — 주문 한 페이지.
   *
   * <p>여기서 돌아오는 배열은 <b>한 화면치</b>다. 전체 건수·매출은 이 배열을 세지 말고
   * {@link getOrderSummary} 를 쓴다. 배열을 세면 페이징이 붙은 지금 그 숫자는 "첫 페이지만
   * 센 값"이 되는데, 화면에는 여전히 숫자가 찍히고 틀렸다고 말해 주는 것이 없다.
   */
  getOrders: async (query: AdminOrderQuery = {}): Promise<AdminOrderPage> => {
    const response = await api.get<AdminOrderPage>('/orders/admin', { params: toParams(query) });
    return response.data;
  },

  /** GET /orders/admin/summary — 같은 조건의 상태별 건수·금액. 페이지에 잘리지 않는다. */
  getOrderSummary: async (query: AdminOrderQuery = {}): Promise<AdminOrderSummary> => {
    const { page: _page, size: _size, ...rest } = query;
    const response = await api.get<AdminOrderSummary>('/orders/admin/summary', {
      params: toParams(rest),
    });
    return response.data;
  },

  /** GET /users/admin/all */
  getAllUsers: async (): Promise<AdminUserResponse[]> => {
    const response = await api.get<AdminUserResponse[]>('/users/admin/all');
    return response.data;
  },
};
