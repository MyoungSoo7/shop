import api from './axios';

/**
 * 감사 로그 조회 API.
 *
 * <p>감사 테이블은 <b>서비스마다 자기 DB 에 따로</b> 있다. order 는 `opslab.audit_logs`,
 * settlement 는 `settlement_db` 의 것이다. MSA 경계상 한쪽이 다른 쪽을 읽을 수 없으므로
 * 표면도 둘이고, 화면은 어느 쪽을 볼지 골라야 한다.
 *
 * <ul>
 *   <li><b>COMMERCE</b> (`/admin/audit-logs`) — 로그인·권한 변경·환불 요청 등 커머스 조작
 *   <li><b>OPERATION</b> (`/api/ops/audit-logs`) — 게시판 생성·수정·닫기·삭제 등 운영 조작
 * </ul>
 *
 * <p><b>정산(`/admin/audit-trail`) 은 여기 없다 — 되살리기 전에 라우트를 먼저 뚫어라.</b>
 * 2026-09-03 까지 이 모듈에는 `SETTLEMENT: '/admin/audit-trail'` 이 있었고 화면에도 탭이 있었지만,
 * 게이트웨이 접두사 목록(`gateway-service/src/main/resources/application.yml`) 어디에도 그 경로가
 * 없었다. shop 안에 그 경로를 받는 컨트롤러도 없다. 즉 탭을 누르면 아무 데도 닿지 않았다.
 * 주석은 "settlement-service 가 답한다"고 적혀 있었지만 주석은 라우트가 아니다.
 *
 * <p>왜 아무도 몰랐나 — 하네스의 두 게이트(`gateway-route-gate`·`api-screen-gate`)는 둘 다
 * 자바 컨트롤러 추출에서 출발한다. 컨트롤러가 없는 프론트 호출은 "빠진" 게 아니라 <b>정의역 밖</b>이라
 * 구조적으로 검출되지 않는다. 그래서 mock 상대로 도는 단위 테스트만 초록으로 남아 있었다.
 *
 * <p>다시 넣으려면 순서는 이렇다: ① 게이트웨이에 settlement 로 가는 라우트를 실제로 등록하고
 * ② 그 경로가 응답하는지 실기동으로 확인한 뒤 ③ 여기에 scope 를 되살린다. ③ 부터 하면 원상복귀다.
 *
 * <p>운영 표면만 `/api/ops` 아래에 있는 것은 게이트웨이 사정이다 — `/admin/audit-logs` 는 이미
 * 커머스로 가는 경로라 뒤에 온 서비스가 그 이름을 다시 쓸 수 없었다. 경로 모양이 다른 것이지
 * 성격이 다른 게 아니다.
 *
 * <p>화면이 반드시 지켜야 할 것 —
 *
 * <ul>
 *   <li><b>기간은 늘 채워 보낸다.</b> 서버가 비면 최근 30일로 채우지만, 화면이 보여 주는 기간과
 *       서버가 조회한 기간이 달라지면 "이 결과가 어느 기간인지" 아무도 확신할 수 없다.
 *   <li><b>내보내기가 잘렸는지 반드시 알린다.</b> 응답 헤더 `X-Export-Truncated` 가 true 면
 *       CSV 는 조건에 맞는 전부가 아니다. 잘린 줄 모르는 파일이 감사 자료로 나가는 것이
 *       가장 나쁜 결말이다.
 * </ul>
 */

/** 어느 서비스의 감사 테이블을 볼 것인가. */
export type AuditScope = 'COMMERCE' | 'OPERATION';

const BASE: Record<AuditScope, string> = {
  // 경로는 전체 리터럴로 적는다(point.ts 와 같은 이유 — grep 으로 배선을 추적할 수 있어야 한다).
  COMMERCE: '/admin/audit-logs',
  OPERATION: '/api/ops/audit-logs',
};

/** 회귀 가드용. 테스트가 "여기 실린 경로가 게이트웨이에 있는가"를 밖에서 대조할 수 있어야 한다. */
export const AUDIT_SCOPE_PATHS: Readonly<Record<AuditScope, string>> = BASE;

export interface AuditLogRow {
  id: number;
  actorId: number | null;
  actorEmail: string | null;
  action: string;
  resourceType: string | null;
  resourceId: string | null;
  /** 조작 전후 값. 기록 시점에 마스킹 계약을 거친 값이라 그대로 보여 준다. */
  detailJson: string | null;
  ipAddress: string | null;
  userAgent: string | null;
  createdAt: string;
}

export interface AuditLogPage {
  content: AuditLogRow[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface AuditActionCount {
  action: string;
  count: number;
}

export interface AuditLogQuery {
  actorEmail?: string;
  actorId?: number;
  action?: string;
  resourceType?: string;
  resourceId?: string;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
}

/** 내보내기 결과 — 파일과 함께 "잘렸는가"를 돌려준다. */
export interface AuditExportResult {
  blob: Blob;
  fileName: string;
  truncated: boolean;
  total: number;
}

/** 빈 문자열·undefined 를 걷어낸 쿼리 파라미터. 빈 값을 보내면 서버가 조건으로 오해한다. */
const params = (query: AuditLogQuery): Record<string, string | number> => {
  const out: Record<string, string | number> = {};
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      out[key] = value as string | number;
    }
  });
  return out;
};

export const auditLogApi = {
  search: async (scope: AuditScope, query: AuditLogQuery) =>
    (await api.get<AuditLogPage>(BASE[scope], { params: params(query) })).data,

  actionCounts: async (scope: AuditScope, query: AuditLogQuery) =>
    (await api.get<AuditActionCount[]>(`${BASE[scope]}/action-counts`, { params: params(query) })).data,

  /** 필터 드롭다운 목록. 서버 enum 이 정본이라 화면에 하드코딩하지 않는다. */
  actions: async (scope: AuditScope) =>
    (await api.get<string[]>(`${BASE[scope]}/actions`)).data,

  export: async (scope: AuditScope, query: AuditLogQuery): Promise<AuditExportResult> => {
    const response = await api.get<Blob>(`${BASE[scope]}/export`, {
      params: params(query),
      responseType: 'blob',
    });

    const disposition = String(response.headers['content-disposition'] ?? '');
    const match = /filename\*?=(?:UTF-8'')?"?([^";]+)"?/i.exec(disposition);

    return {
      blob: response.data,
      fileName: match ? decodeURIComponent(match[1]) : 'audit-logs.csv',
      truncated: String(response.headers['x-export-truncated']) === 'true',
      total: Number(response.headers['x-export-total'] ?? 0),
    };
  },
};

/**
 * Blob 을 파일로 저장시킨다.
 *
 * <p>오브젝트 URL 을 반드시 해제한다 — 해제하지 않으면 콘솔을 오래 열어 둔 운영자의 브라우저에
 * 내려받은 CSV 가 통째로 쌓인다.
 */
export const saveBlob = (blob: Blob, fileName: string) => {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  URL.revokeObjectURL(url);
};
