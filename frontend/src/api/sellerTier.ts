import api from './axios';

/**
 * 셀러 등급 운영 콘솔 — order-service {@code AdminSellerTierController}
 * (`/admin/seller-tiers/**`, SecurityConfig 가 **ADMIN** 으로 게이트, ADR 0031).
 *
 * <p>등급 하나가 수수료율·정산주기·홀드백을 <b>동시에</b> 바꾼다. 그래서 두 가지가 규약이다:
 * <ul>
 *   <li>재산정은 {@code dryRun} 기본 true — 파라미터를 빠뜨린 호출이 전 셀러 등급을 바꾸면 안 된다.
 *   <li>관리자 지정에는 <b>사유가 필수</b>다. 근거 없는 등급 변경이 이력에 쌓이면 감사가 의미를 잃는다.
 * </ul>
 *
 * <p>정합 검사가 따로 있는 이유: {@code users.seller_tier} 는 캐시이고, 결제는 그 <b>캐시값</b>을
 * 이벤트에 실어 정산을 확정한다. 정산은 스냅샷이라 나중에 정본을 고쳐도 되돌아오지 않는다 —
 * 드리프트는 결제 전에 잡아야 하는 것이다.
 */

export type SellerTierGrade = 'NORMAL' | 'VIP' | 'STRATEGIC';

export const SELLER_TIER_LABEL: Record<SellerTierGrade, string> = {
  NORMAL: '일반',
  VIP: 'VIP',
  STRATEGIC: '전략',
};

/** 재산정 행 1건 — `outcome` 은 서버 판정을 그대로 싣는다(화면이 다시 계산하지 않는다). */
export interface TierEvaluationLine {
  sellerId: number;
  fromTier: string | null;
  toTier: string | null;
  outcome: string;
  netSales: number;
  reason: string | null;
}

/** `dryRun` 이 true 면 아무것도 바뀌지 않은 미리보기다. */
export interface TierEvaluationReport {
  evaluated: number;
  promoted: number;
  demoted: number;
  held: number;
  guarded: number;
  failed: number;
  dryRun: boolean;
  lines: TierEvaluationLine[];
}

/** 정본↔캐시가 어긋난 한 건. 등급 문자열은 파싱하지 않고 서버가 준 그대로다. */
export interface TierCacheDrift {
  sellerId: number;
  authoritativeTier: string | null;
  cachedTier: string | null;
  kind: 'CACHE_STALE' | 'CACHE_MISSING' | 'AUTHORITY_MISSING';
}

export interface TierIntegrityReport {
  drifted: number;
  byKind: Record<string, number>;
  samples: TierCacheDrift[];
  /** 등급 문자열이 enum 밖이라 판독하지 못한 행 — 0 이 아니면 그 자체가 조사 대상이다. */
  unreadable: number;
}

/** 지금 적용 중인 임계. 배포 환경마다 다를 수 있어, 이걸 모르면 미리보기 결과를 해석할 수 없다. */
export interface SellerTierPolicyView {
  vipThreshold: number;
  strategicThreshold: number;
}

export interface TierAssignmentView {
  sellerId: number;
  tier: string;
  effectiveFrom: string;
  /** 이 날짜까지는 자동 재산정이 강등하지 못한다. */
  demotionGuardUntil: string | null;
}

export const sellerTierApi = {
  /**
   * POST /evaluate — 등급 재산정.
   *
   * <p>`dryRun` 기본값을 true 로 둔 것은 서버 규약을 그대로 따른 것이다. 여기서 false 로
   * 뒤집으면 서버가 세운 안전장치를 클라이언트가 무력화하게 된다.
   */
  evaluate: async (dryRun = true, date?: string, limit?: number): Promise<TierEvaluationReport> =>
    (await api.post<TierEvaluationReport>('/admin/seller-tiers/evaluate', null,
      { params: { dryRun, date, limit } })).data,

  /** POST /{sellerId}/override — 사유 필수. 지정자는 서버가 인증 주체에서 딴다(본문으로 못 보낸다). */
  override: async (sellerId: number, tier: SellerTierGrade, memo: string): Promise<TierAssignmentView> =>
    (await api.post<TierAssignmentView>(`/admin/seller-tiers/${sellerId}/override`, { tier, memo })).data,

  /** GET /integrity — 읽기 전용. 정본과 캐시의 불일치를 센다. */
  integrity: async (sampleLimit?: number): Promise<TierIntegrityReport> =>
    (await api.get<TierIntegrityReport>('/admin/seller-tiers/integrity', { params: { sampleLimit } })).data,

  /** GET /policy — 적용 중인 등급 임계. */
  policy: async (): Promise<SellerTierPolicyView> =>
    (await api.get<SellerTierPolicyView>('/admin/seller-tiers/policy')).data,
};
