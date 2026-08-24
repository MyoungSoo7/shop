import { isAxiosError } from 'axios';
import api from './axios';

/**
 * 조직·멤버십 — organization-service {@code OrganizationController} (`/api/organizations`).
 *
 * <p>이 서비스는 <b>화면이 하나도 없었다</b>. 조직을 만들고 사람을 붙이는 경로가 API 뿐이라,
 * 셀러/기업 조직 구조는 실질적으로 운영할 수 없는 상태였다.
 *
 * <p><b>요청자는 JWT 에서만 파생한다.</b> 조직 내 역할 인가는 서버의 {@code OrgAuthorizer} 가
 * 판정하므로, 화면이 "내가 누구인지"를 실어 보낼 자리가 없다(IDOR 차단).
 *
 * <p><b>수락은 본인만 한다.</b> {@code POST /{orgId}/members/accept} 는 <b>호출자 자신의</b>
 * 초대를 수락한다 — 관리자가 남의 초대를 대신 승인하는 API 가 아니다. 화면이 이 구분을 흐리면
 * 관리자는 "승인 버튼"으로 착각하고 자기 멤버십을 만들어 버린다.
 *
 * <p><b>목록 조회가 없다.</b> 조직 상세({@code GET /{orgId}})만 있어 조직 번호를 직접 받는다.
 */

export type OrgRole = 'OWNER' | 'MANAGER' | 'STAFF';
export type OrgType = 'SELLER' | 'CORPORATE';
export type MembershipStatus = 'INVITED' | 'ACTIVE' | 'SUSPENDED' | 'REMOVED';

export interface Membership {
  userId: number;
  role: OrgRole;
  status: MembershipStatus;
  invitedBy: number | null;
}

export interface Organization {
  id: number;
  name: string;
  type: OrgType;
  externalRef: string | null;
  status: 'ACTIVE' | 'SUSPENDED';
  /** 생성 응답에서는 <b>빈 목록</b>이고 조회에서만 채워진다(서버 주석). */
  members: Membership[];
}

export interface CreateOrganizationInput {
  name: string;
  type: OrgType;
  externalRef?: string;
}

/**
 * 마지막 OWNER 를 강등·제거하려 했다 — 서버가 422 로 거절한다.
 *
 * <p>일반 오류와 갈라 두는 이유: 이건 실패가 아니라 <b>도메인이 지키는 불변식</b>이다
 * (활성 OWNER 는 최소 1명). 화면이 "실패했습니다"로 뭉개면 운영자는 재시도만 반복한다.
 */
export class LastOwnerError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'LastOwnerError';
  }
}

const call = async <T>(request: () => Promise<{ data: T }>): Promise<T> => {
  try {
    return (await request()).data;
  } catch (err) {
    if (isAxiosError(err) && err.response?.status === 422) {
      const message = (err.response.data as { message?: string })?.message;
      throw new LastOwnerError(message || '활성 OWNER 는 최소 1명이어야 합니다.');
    }
    throw err;
  }
};

export const organizationApi = {
  /** 조직 상세 + 멤버 목록. 없으면 `null`. */
  detail: async (orgId: number): Promise<Organization | null> => {
    try {
      return (await api.get<Organization>(`/api/organizations/${orgId}`)).data;
    } catch (err) {
      if (isAxiosError(err) && err.response?.status === 404) return null;
      throw err;
    }
  },

  /** 조직 생성 — 만든 사람이 OWNER 가 된다(서버가 호출자에서 파생). */
  create: async (input: CreateOrganizationInput): Promise<Organization> =>
    (await api.post<Organization>('/api/organizations', input)).data,

  /** 초대 — 상태 INVITED 로 만들어진다. 활성 슬롯이 이미 차 있으면 409. */
  invite: (orgId: number, targetUserId: number, role: OrgRole): Promise<Membership> =>
    call(() => api.post<Membership>(`/api/organizations/${orgId}/members`, { targetUserId, role })),

  /** <b>본인</b>의 초대를 수락한다 — 남을 대신 수락할 수 없다. */
  acceptOwnInvite: (orgId: number): Promise<Membership> =>
    call(() => api.post<Membership>(`/api/organizations/${orgId}/members/accept`)),

  /** 역할 변경. 마지막 OWNER 강등이면 {@link LastOwnerError}. */
  changeRole: (orgId: number, userId: number, newRole: OrgRole): Promise<Membership> =>
    call(() => api.patch<Membership>(
      `/api/organizations/${orgId}/members/${userId}/role`, { newRole })),

  /** 멤버 제거. 마지막 OWNER 제거면 {@link LastOwnerError}. */
  remove: (orgId: number, userId: number): Promise<void> =>
    call(() => api.delete<void>(`/api/organizations/${orgId}/members/${userId}`)),
};
