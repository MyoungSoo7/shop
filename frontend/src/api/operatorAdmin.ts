import api from './axios';

/**
 * 권한 계정 운영 — order-service {@code AdminOperatorController}.
 *
 * <p><b>회원 관리와 다른 화면인 이유.</b> {@code /admin/members} 는 "누가 우리 서비스를 쓰는가"를
 * 본다. 여기는 "누가 우리 시스템을 <b>조작할 수 있는가</b>"를 본다. 둘은 같은 users 테이블에
 * 살지만 위험의 성격이 다르다 — 방치된 일반 회원은 아무 일도 일으키지 않지만, 방치된 ADMIN
 * 계정은 남의 손에 들어가는 순간 전 권한이다.
 *
 * <p>그래서 목록의 기본 정렬이 <b>마지막 로그인이 오래된 순</b>이다. 안 쓰는 계정이 먼저 보여야
 * 회수할 대상을 고를 수 있다. 이름·이메일 검색을 먼저 두면 "이미 아는 계정"만 확인하게 된다.
 *
 * <p><b>잠금 해제에 사유가 필수인 이유</b>: 잠금은 연속 로그인 실패로 걸린다. 그 잠금을 푸는 것은
 * 공격 시도를 무효화하는 동작일 수도, 본인이 비밀번호를 잊은 것을 풀어 주는 동작일 수도 있다.
 * 둘을 나중에 구분할 수 있는 유일한 근거가 그때 적은 사유다 — 서버가 감사 로그에 남긴다.
 */

export interface OperatorSummary {
  id: number;
  email: string;
  name: string;
  /** ADMIN · MANAGER — 서버 enum 이 정본이라 화면에 매핑표를 두지 않는다. */
  role: string;
  active: boolean;
  /** 한 번도 로그인한 적이 없으면 null. "오래 안 썼다"와 "쓴 적이 없다"는 다른 상태다. */
  lastLoginAt: string | null;
  failedLoginAttempts: number;
  /** 잠금 해제 예정 시각. 잠겨 있지 않으면 null. */
  lockedUntil: string | null;
  /**
   * 지금 잠겨 있는가. {@code lockedUntil} 이 과거면 시간이 지나 이미 풀린 것이므로
   * 화면이 날짜를 비교해 다시 판정하지 않는다 — 서버 시계로 판정한 값을 그대로 쓴다.
   */
  locked: boolean;
  passwordChangedAt: string | null;
  createdAt: string;
}

export interface OperatorPage {
  content: OperatorSummary[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface OperatorQuery {
  keyword?: string;
  role?: string;
  /** 잠긴 계정만. */
  lockedOnly?: boolean;
  /** 마지막 로그인이 N일 이상 지난 계정만 — 회수 후보를 좁히는 축이다. */
  idleDays?: number;
  /** 한 번도 로그인하지 않은 계정만. {@code idleDays} 로는 잡히지 않는 부류다. */
  neverLoggedIn?: boolean;
  page?: number;
  size?: number;
}

export interface OperatorUnlockResult {
  userId: number;
  email: string;
  /**
   * 실제로 잠겨 있었는가. 목록을 열어 둔 사이 시간이 지나 저절로 풀렸을 수 있다 —
   * 이때 화면이 "해제했습니다"라고만 하면 하지도 않은 일을 했다고 보고하는 셈이다.
   */
  wasLocked: boolean;
  previousLockedUntil: string | null;
  previousFailedAttempts: number;
}

export interface OperatorExportResult {
  blob: Blob;
  fileName: string;
  truncated: boolean;
  total: number;
}

/** 빈 문자열·undefined 를 걷어낸 쿼리 파라미터. 빈 값을 보내면 서버가 조건으로 오해한다. */
const params = (query: OperatorQuery): Record<string, string | number | boolean> => {
  const out: Record<string, string | number | boolean> = {};
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '' && value !== false) {
      out[key] = value as string | number | boolean;
    }
  });
  return out;
};

export const operatorAdminApi = {
  // 경로는 전체 리터럴로 적는다 — grep 으로 배선을 추적할 수 있어야 한다.
  search: async (query: OperatorQuery): Promise<OperatorPage> =>
    (await api.get<OperatorPage>('/admin/operators', { params: params(query) })).data,

  /** 같은 조건의 CSV. 서버가 감사 로그(OPERATOR_LIST_EXPORTED)를 남긴다 — 권한 계정 명부다. */
  export: async (query: OperatorQuery): Promise<OperatorExportResult> => {
    const { page: _page, size: _size, ...rest } = query;
    const response = await api.get<Blob>('/admin/operators/export', {
      params: params(rest),
      responseType: 'blob',
    });

    const disposition = String(response.headers['content-disposition'] ?? '');
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);

    return {
      blob: response.data,
      fileName: match ? decodeURIComponent(match[1]) : 'operators.csv',
      truncated: String(response.headers['x-export-truncated']) === 'true',
      total: Number(response.headers['x-export-total'] ?? 0),
    };
  },

  /** 잠금 해제. 사유는 서버가 필수로 받는다(감사 기록에 그대로 남는다). */
  unlock: async (userId: number, reason: string): Promise<OperatorUnlockResult> =>
    (await api.post<OperatorUnlockResult>(`/admin/operators/${userId}/unlock`, { reason })).data,
};
