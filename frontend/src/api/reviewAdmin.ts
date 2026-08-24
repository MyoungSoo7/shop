import api from './axios';

/**
 * 리뷰 관리 API.
 *
 * <p>화면이 반드시 지켜야 할 것 —
 *
 * <ul>
 *   <li><b>블라인드는 삭제가 아니다.</b> 원문은 남고 공개 목록에서만 빠진다. 화면 문구가
 *       "삭제"라고 말하면 운영자는 되돌릴 수 없는 조작이라 믿고 주저하거나, 반대로 지웠다고
 *       오해한다.
 *   <li><b>사유 없이 숨기지 않는다.</b> 작성자 이의 제기와 감사 양쪽에 사유가 필요하다.
 *       서버도 막지만 화면이 먼저 막아야 400 으로 배우지 않는다.
 * </ul>
 */

export interface ReviewRow {
  id: number;
  productId: number;
  productName: string | null;
  userId: number;
  userEmail: string | null;
  rating: number;
  content: string | null;
  status: string;
  hiddenReason: string | null;
  hiddenBy: number | null;
  hiddenAt: string | null;
  createdAt: string;
}

export interface ReviewPage {
  content: ReviewRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface ReviewStatusCount {
  status: string;
  count: number;
}

export interface ReviewQuery {
  keyword?: string;
  productId?: number;
  userId?: number;
  status?: string;
  maxRating?: number;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

export interface ReviewExportResult {
  blob: Blob;
  fileName: string;
  truncated: boolean;
  total: number;
}

/** 빈 값을 걷어낸 쿼리 파라미터. 빈 문자열을 보내면 서버가 조건으로 오해한다. */
const params = (query: ReviewQuery): Record<string, string | number> => {
  const out: Record<string, string | number> = {};
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      out[key] = value as string | number;
    }
  });
  return out;
};

export const reviewAdminApi = {
  // 경로는 전체 리터럴로 적는다 — grep 으로 배선을 추적할 수 있어야 한다.
  search: async (query: ReviewQuery) =>
    (await api.get<ReviewPage>('/admin/reviews', { params: params(query) })).data,

  statusCounts: async (query: ReviewQuery) =>
    (await api.get<ReviewStatusCount[]>('/admin/reviews/status-counts', { params: params(query) })).data,

  statuses: async () => (await api.get<string[]>('/admin/reviews/statuses')).data,

  hide: async (reviewId: number, reason: string) =>
    (await api.post(`/admin/reviews/${reviewId}/hide`, { reason })).data,

  restore: async (reviewId: number) =>
    (await api.post(`/admin/reviews/${reviewId}/restore`)).data,

  export: async (query: ReviewQuery): Promise<ReviewExportResult> => {
    const response = await api.get<Blob>('/admin/reviews/export', {
      params: params(query),
      responseType: 'blob',
    });

    const disposition = String(response.headers['content-disposition'] ?? '');
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);

    return {
      blob: response.data,
      fileName: match ? decodeURIComponent(match[1]) : 'reviews.csv',
      truncated: String(response.headers['x-export-truncated']) === 'true',
      total: Number(response.headers['x-export-total'] ?? 0),
    };
  },
};
