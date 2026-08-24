import api from './axios';

/**
 * 포인트 원장 API.
 *
 * <p>백엔드 설계는 `docs/plan/point-ledger.md`. 화면이 알아야 할 것은 두 가지뿐이다 —
 * 수기 지급에는 <b>사유가 필수</b>이고, 소멸 실행은 <b>미리보기가 기본</b>이라는 것.
 */

/** 수기 지급 결과. `entryId` 가 null 이면 같은 referenceId 로 이미 지급된 건이다(멱등 단축 반환). */
export interface GrantPointResult {
  entryId: number | null;
  lotId: number | null;
  grantedAmount: number;
  remainingBalance: number;
}

/** 소멸 결과. `dryRun` 이 true 면 아무것도 바뀌지 않은 미리보기다. */
export interface ExpirePointResult {
  lotCount: number;
  accountCount: number;
  forfeitedTotal: number;
  dryRun: boolean;
}

export interface PointBalance {
  userId: number;
  available: number;
}

/**
 * 원장 3자 대조 — 계정 잔고 · ACTIVE 로트 합계 · 원장 누계.
 *
 * <p>셋은 갱신 경로가 달라서, 어긋났다면 잔고만 움직이고 기록이 빠진 트랜잭션이 있었다는 뜻이다.
 * 화면은 이 값을 <b>판정</b>이 아니라 <b>조사 신호</b>로 그린다.
 */
export interface PointLedgerHealth {
  accountTotal: number;
  activeLotRemaining: number;
  entryNet: number;
}

export interface PointConsoleSummary {
  accountCount: number;
  totalBalance: number;
  totalActiveLotRemaining: number;
  totalEntryNet: number;
  /** 0 이 아니면 조사 대상 계정이 있다는 뜻. */
  driftedAccountCount: number;
  expiringWithinDays: number;
  expiringAmount: number;
}

export interface PointLotView {
  lotId: number;
  origin: string;
  originalAmount: number;
  remainingAmount: number;
  status: string;
  grantedAt: string;
  /** null 이면 무기한 로트 — 소멸 대상이 아니다. */
  expiresAt: string | null;
  referenceType: string;
  referenceId: string;
}

export interface PointEntryView {
  entryId: number;
  entryType: string;
  amount: number;
  referenceType: string;
  referenceId: string;
  memo: string | null;
  createdBy: string;
  createdAt: string;
}

export interface PointAccountDetail {
  userId: number;
  accountId: number;
  status: string;
  available: number;
  locked: number;
  total: number;
  health: PointLedgerHealth;
  lots: PointLotView[];
  entries: PointEntryView[];
}

/** 적립률 정책의 범위. 서버 enum(PointEarnScope)과 1:1. */
export type PointEarnScope = 'GLOBAL' | 'GRADE' | 'CATEGORY';

export interface PointEarnPolicyView {
  id: number;
  scope: string;
  scopeKey: string;
  earnRate: number;
  validityDays: number;
  effectiveFrom: string;
  effectiveTo: string | null;
  reason: string;
  createdBy: string;
  /** 오늘 기준 적용 여부 — <b>날짜 범위만</b>으로 판정된 값이다. */
  active: boolean;
  /**
   * 운영자가 종료를 지정한 시각. 적용 여부가 아니다 — 종료일이 미래면 그날까지 `active` 는 참이다.
   * "언제 끊었나"의 감사 기록.
   */
  closedAt: string | null;
}

/** 정책은 고치지 않는다 — 종료 + 신규 등록 2단계다(ADR 0032). */
export interface RegisterPolicyRequest {
  scope: PointEarnScope;
  /** GLOBAL 은 관례상 '-', GRADE·CATEGORY 는 등급명·카테고리 코드. */
  scopeKey: string;
  /** 0~1 비율(0.01 = 1%). 화면의 % 입력은 호출 전에 100 으로 나눈다. */
  earnRate: number;
  validityDays: number;
  /** 오늘 이상이어야 한다 — 소급 발효는 400. */
  effectiveFrom: string;
  effectiveTo?: string | null;
  reason: string;
}

export interface ManualDeductRequest {
  userId: number;
  amount: number;
  /** 멱등 키 — 같은 값으로 두 번 눌러도 한 번만 빠진다. */
  referenceId: string;
  /** 차감 근거. 없으면 고객 재산이 왜 줄었는지 설명할 수 없다. */
  reason: string;
}

/** `entryId` 가 null 이면 같은 referenceId 로 이미 차감된 건이다(멱등 단축 반환). */
export interface DeductPointResult {
  entryId: number | null;
  deductedAmount: number;
  remainingBalance: number;
}

export interface ExpiringLotView {
  userId: number;
  lotId: number;
  origin: string;
  remainingAmount: number;
  expiresAt: string;
}

export interface ManualGrantRequest {
  userId: number;
  amount: number;
  /** 멱등 키 — 같은 값으로 두 번 눌러도 한 번만 지급된다(원장 자연키). */
  referenceId: string;
  /** 지급 근거. 없으면 나중에 "왜 이 돈이 여기 있나"에 답할 수 없다. */
  reason: string;
  /** null 이면 무기한. */
  validityDays?: number | null;
}

// 경로는 <b>전체 리터럴</b>로 적는다. 조각을 이어 붙이면 사람 눈에도, 저장소의 화면-API 대조
// 게이트(api-screen-gate)에도 어떤 엔드포인트를 부르는지 보이지 않는다.
export const pointApi = {
  grant: async (body: ManualGrantRequest) =>
    (await api.post<GrantPointResult>('/admin/points/grants', body)).data,

  /** 기본은 미리보기다 — 호출부가 dryRun 을 빠뜨려도 실행되지 않는다. */
  runExpiry: async (dryRun = true, batchSize = 500) =>
    (await api.post<ExpirePointResult>('/admin/points/expiry/run', null, {
      params: { dryRun, batchSize },
    })).data,

  myBalance: async () => (await api.get<PointBalance>('/api/points/me')).data,

  summary: async (withinDays = 30) =>
    (await api.get<PointConsoleSummary>('/admin/points/summary', {
      params: { withinDays },
    })).data,

  /** 계정이 없는 사용자는 404 다 — 잔액 0 인 계정과 구분되므로 호출부가 그대로 안내한다. */
  account: async (userId: number) =>
    (await api.get<PointAccountDetail>(`/admin/points/accounts/${userId}`)).data,

  policies: async () =>
    (await api.get<PointEarnPolicyView[]>('/admin/points/policies')).data,

  expiring: async (withinDays = 30, limit = 50) =>
    (await api.get<ExpiringLotView[]>('/admin/points/expiring', {
      params: { withinDays, limit },
    })).data,

  /** 수기 차감 — 지급의 역방향. 잔액을 넘으면 422 로 거절된다. */
  deduct: async (body: ManualDeductRequest) =>
    (await api.post<DeductPointResult>('/admin/points/deductions', body)).data,

  /** 정책 등록 — 같은 범위에 기간이 겹치면 409. 현재 정책을 먼저 종료해야 한다. */
  registerPolicy: async (body: RegisterPolicyRequest) =>
    (await api.post<PointEarnPolicyView>('/admin/points/policies', body)).data,

  /** 정책 종료 — 종료일부터 적용하지 않는다(반열림). 과거 날짜는 400. */
  closePolicy: async (policyId: number, effectiveTo: string) =>
    (await api.post<PointEarnPolicyView>(
      `/admin/points/policies/${policyId}/close`, { effectiveTo })).data,
};
