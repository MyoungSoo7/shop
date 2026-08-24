import api from './axios';

export type BulkOrderStatus = 'UPLOADED' | 'VALIDATED' | 'REJECTED' | 'CONFIRMED' | 'DISCARDED';

export interface BulkOrderColumn {
  columnIndex: number;
  itemCode: string;
  name: string;
  required: boolean;
  maxLength: number | null;
  validationType: string;
  validationText: string | null;
}

export interface BulkOrderCell {
  columnIndex: number;
  value: string | null;
  valid: boolean;
  errorMessage: string | null;
}

export interface BulkOrderRow {
  rowNumber: number;
  valid: boolean;
  errorMessage: string | null;
  createdOrderId: number | null;
  cells: BulkOrderCell[];
}

export interface BulkOrderDraft {
  id: number;
  fileName: string | null;
  status: BulkOrderStatus;
  rowCount: number;
  validRowCount: number;
  uploadedAt: string;
  updatedAt: string;
  rows: BulkOrderRow[];
}

export interface ConfirmResult {
  draftId: number;
  status: BulkOrderStatus;
  created: number;
  failed: number;
  lines: { rowNumber: number; orderId: number | null; error: string | null }[];
}

/**
 * 대량주문 — 업로드/검증과 확정이 다른 호출이다.
 *
 * 올리는 순간 주문이 나가면 뒷쪽 한 행의 오타 때문에 앞쪽 수백 건을 취소·환불로 되돌려야 한다.
 */
export const bulkOrderApi = {
  columns: async (): Promise<BulkOrderColumn[]> =>
    (await api.get<BulkOrderColumn[]>('/api/bulk-orders/columns')).data,

  list: async (): Promise<BulkOrderDraft[]> =>
    (await api.get<BulkOrderDraft[]>('/api/bulk-orders')).data,

  get: async (id: number): Promise<BulkOrderDraft> =>
    (await api.get<BulkOrderDraft>(`/api/bulk-orders/${id}`)).data,

  upload: async (file: File): Promise<BulkOrderDraft> => {
    const form = new FormData();
    form.append('file', file);
    // Content-Type 을 지우는 이유: axios 기본값(application/json)이 남으면 multipart boundary 가
    // 실리지 않아 서버가 파트를 하나도 못 읽는다.
    const response = await api.post<BulkOrderDraft>('/api/bulk-orders', form, {
      headers: { 'Content-Type': undefined as unknown as string },
    });
    return response.data;
  },

  revalidate: async (id: number): Promise<BulkOrderDraft> =>
    (await api.post<BulkOrderDraft>(`/api/bulk-orders/${id}/revalidate`)).data,

  confirm: async (id: number): Promise<ConfirmResult> =>
    (await api.post<ConfirmResult>(`/api/bulk-orders/${id}/confirm`)).data,

  discard: async (id: number): Promise<void> => {
    await api.delete(`/api/bulk-orders/${id}`);
  },
};
