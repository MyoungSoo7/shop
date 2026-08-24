import api from './axios';

/**
 * 쿠폰 운영 API.
 *
 * <p>화면이 반드시 지켜야 할 것 —
 *
 * <ul>
 *   <li><b>수명 상태는 서버가 정한다.</b> `isActive`·`expiresAt`·`usedCount/maxUses` 를 화면에서
 *       조합해 "만료됨"을 판정하면 화면마다 판정이 조금씩 달라진다. 서버가 내려주는
 *       `lifecycle` 을 그대로 쓴다.
 *   <li><b>중단은 삭제가 아니다.</b> 이미 사용된 쿠폰을 지우면 사용 이력의 참조가 끊겨 그 할인이
 *       어디서 왔는지 설명할 수 없다. 그래서 삭제 API 자체가 없다.
 *   <li><b>maxUses 0 은 무제한</b>이다. 0 을 "발급 불가"로 읽으면 살아 있는 쿠폰을 죽은 것으로
 *       표시하게 된다.
 * </ul>
 */

/** 서버 enum(CouponLifecycle)과 1:1. 우선순위: 꺼짐 → 시작 전 → 만료 → 소진 → 활성. */
export type CouponLifecycle = 'INACTIVE' | 'SCHEDULED' | 'EXPIRED' | 'EXHAUSTED' | 'ACTIVE';

export interface CouponRow {
  id: number;
  code: string;
  type: string;
  discountValue: number;
  minOrderAmount: number | null;
  maxDiscountAmount: number | null;
  /** 0 이면 무제한. */
  maxUses: number;
  usedCount: number;
  targetType: string | null;
  targetId: number | null;
  startsAt: string | null;
  expiresAt: string | null;
  active: boolean;
  lifecycle: string;
  createdAt: string;
}

export interface CouponPage {
  content: CouponRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface CouponLifecycleCount {
  lifecycle: string;
  count: number;
}

export interface CouponEnums {
  lifecycles: string[];
  types: string[];
}

/** 사용 내역. `revokedAt` 이 있으면 주문 취소·환불로 되돌려진 이력이다. */
export interface CouponUsageRow {
  id: number;
  userId: number;
  userEmail: string | null;
  orderId: number | null;
  usedAt: string;
  revokedAt: string | null;
  revokeReason: string | null;
}

export interface CouponQuery {
  code?: string;
  lifecycle?: string;
  type?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface CouponExportResult {
  blob: Blob;
  fileName: string;
  truncated: boolean;
  total: number;
}

/** 빈 값을 걷어낸 쿼리 파라미터. */
const params = (query: CouponQuery): Record<string, string | number> => {
  const out: Record<string, string | number> = {};
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      out[key] = value as string | number;
    }
  });
  return out;
};

export const couponAdminApi = {
  // 경로는 전체 리터럴로 적는다 — grep 으로 배선을 추적할 수 있어야 한다.
  search: async (query: CouponQuery) =>
    (await api.get<CouponPage>('/admin/coupons', { params: params(query) })).data,

  lifecycleCounts: async (query: CouponQuery) =>
    (await api.get<CouponLifecycleCount[]>('/admin/coupons/lifecycle-counts', { params: params(query) })).data,

  enums: async () => (await api.get<CouponEnums>('/admin/coupons/enums')).data,

  usages: async (couponId: number, limit = 100) =>
    (await api.get<CouponUsageRow[]>(`/admin/coupons/${couponId}/usages`, { params: { limit } })).data,

  activate: async (code: string) =>
    (await api.post(`/admin/coupons/${encodeURIComponent(code)}/activate`)).data,

  deactivate: async (code: string) =>
    (await api.post(`/admin/coupons/${encodeURIComponent(code)}/deactivate`)).data,

  export: async (query: CouponQuery): Promise<CouponExportResult> => {
    const response = await api.get<Blob>('/admin/coupons/export', {
      params: params(query),
      responseType: 'blob',
    });

    const disposition = String(response.headers['content-disposition'] ?? '');
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);

    return {
      blob: response.data,
      fileName: match ? decodeURIComponent(match[1]) : 'coupons.csv',
      truncated: String(response.headers['x-export-truncated']) === 'true',
      total: Number(response.headers['x-export-total'] ?? 0),
    };
  },
};
