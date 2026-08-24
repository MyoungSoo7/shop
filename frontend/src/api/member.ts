import api from './axios';

/**
 * 회원 관리 API.
 *
 * <p>조회·역할변경은 `/admin/members`(회원 콘솔), 승인·반려·정지·복구는 기존
 * `/memberships`(승인 워크플로)다. <b>표면이 둘인 것은 실수가 아니라 의도</b>다 —
 * 상태 전이는 `membership_approvals` 이력까지 남기는 서비스가 이미 소유하고 있고,
 * 같은 조작을 두 표면에 두면 언젠가 한쪽만 고쳐져 이력이 갈라진다.
 *
 * <p>화면이 반드시 지켜야 할 것 —
 *
 * <ul>
 *   <li><b>CSV 내보내기는 감사에 남는다.</b> 목록을 보는 것과 개인정보를 파일로 가져가는 것은
 *       같은 무게가 아니다. 화면은 이 사실을 사용자에게 숨기지 않는다.
 *   <li><b>잘린 CSV 를 조용히 넘기지 않는다.</b> `X-Export-Truncated` 가 true 면 그 파일은
 *       조건에 맞는 전부가 아니다.
 *   <li><b>역할 변경 사유는 비울 수 없다.</b> 서버도 막지만, 화면이 먼저 막아야 운영자가
 *       "왜 실패했는지"를 400 응답으로 배우지 않는다.
 * </ul>
 */

export interface MemberSummary {
  id: number;
  email: string;
  name: string | null;
  phoneNumber: string | null;
  role: string;
  membershipStatus: string;
  active: boolean;
  createdAt: string;
  updatedAt: string | null;
}

export interface MemberPage {
  content: MemberSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface MemberStatusCount {
  membershipStatus: string;
  count: number;
}

export interface MemberEnums {
  roles: string[];
  membershipStatuses: string[];
}

export interface MemberQuery {
  keyword?: string;
  role?: string;
  status?: string;
  active?: boolean;
  joinedFrom?: string;
  joinedTo?: string;
  page?: number;
  size?: number;
}

export interface MemberExportResult {
  blob: Blob;
  fileName: string;
  truncated: boolean;
  total: number;
}

/** 빈 문자열·undefined 를 걷어낸 쿼리 파라미터. 빈 값을 보내면 서버가 조건으로 오해한다. */
const params = (query: MemberQuery): Record<string, string | number | boolean> => {
  const out: Record<string, string | number | boolean> = {};
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      out[key] = value as string | number | boolean;
    }
  });
  return out;
};

export const memberApi = {
  // 경로는 전체 리터럴로 적는다 — grep 으로 배선을 추적할 수 있어야 한다.
  search: async (query: MemberQuery) =>
    (await api.get<MemberPage>('/admin/members', { params: params(query) })).data,

  statusCounts: async (query: MemberQuery) =>
    (await api.get<MemberStatusCount[]>('/admin/members/status-counts', { params: params(query) })).data,

  /** 필터 드롭다운 목록. 서버 enum 이 정본이라 화면에 하드코딩하지 않는다. */
  enums: async () => (await api.get<MemberEnums>('/admin/members/enums')).data,

  export: async (query: MemberQuery): Promise<MemberExportResult> => {
    const response = await api.get<Blob>('/admin/members/export', {
      params: params(query),
      responseType: 'blob',
    });

    const disposition = String(response.headers['content-disposition'] ?? '');
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);

    return {
      blob: response.data,
      fileName: match ? decodeURIComponent(match[1]) : 'members.csv',
      truncated: String(response.headers['x-export-truncated']) === 'true',
      total: Number(response.headers['x-export-total'] ?? 0),
    };
  },

  changeRole: async (userId: number, role: string, reason: string) =>
    (await api.patch<MemberSummary>(`/admin/members/${userId}/role`, { role, reason })).data,

  // ── 승인 워크플로 (기존 표면). 이력은 membership_approvals 가 남긴다. ──
  approve: async (userId: number) => (await api.post(`/memberships/${userId}/approve`)).data,
  reject: async (userId: number, reason: string) =>
    (await api.post(`/memberships/${userId}/reject`, { reason })).data,
  suspend: async (userId: number, reason: string) =>
    (await api.post(`/memberships/${userId}/suspend`, { reason })).data,
  reinstate: async (userId: number) => (await api.post(`/memberships/${userId}/reinstate`)).data,
};
