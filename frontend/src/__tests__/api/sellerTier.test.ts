import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  SELLER_TIER_LABEL,
  sellerTierApi,
  type SellerTierGrade,
  type SellerTierPolicyView,
  type TierAssignmentView,
  type TierEvaluationReport,
  type TierIntegrityReport,
} from '@/api/sellerTier';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const report: TierEvaluationReport = {
  evaluated: 10,
  promoted: 2,
  demoted: 1,
  held: 6,
  guarded: 1,
  failed: 0,
  dryRun: true,
  lines: [
    {
      sellerId: 1,
      fromTier: 'NORMAL',
      toTier: 'VIP',
      outcome: 'PROMOTED',
      netSales: 120_000_000,
      reason: null,
    },
  ],
};

describe('sellerTierApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('재산정은 dryRun 기본 true 로 나간다', async () => {
    // 등급 하나가 수수료율·정산주기·홀드백을 동시에 바꾼다. 파라미터를 빠뜨린 호출이
    // 전 셀러 등급을 실제로 바꾸면 되돌릴 방법이 없다(정산은 스냅샷이다).
    vi.mocked(api.post).mockResolvedValueOnce({ data: report });

    await sellerTierApi.evaluate();

    expect(api.post).toHaveBeenCalledWith('/admin/seller-tiers/evaluate', null, {
      params: { dryRun: true, date: undefined, limit: undefined },
    });
  });

  it('실행은 dryRun=false 를 명시했을 때만이다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: { ...report, dryRun: false } });

    const result = await sellerTierApi.evaluate(false, '2026-08-01', 50);

    expect(api.post).toHaveBeenCalledWith('/admin/seller-tiers/evaluate', null, {
      params: { dryRun: false, date: '2026-08-01', limit: 50 },
    });
    expect(result.dryRun).toBe(false);
  });

  it('판정(outcome)은 서버 값을 그대로 싣는다', async () => {
    vi.mocked(api.post).mockResolvedValueOnce({ data: report });

    const result = await sellerTierApi.evaluate();

    // 화면이 다시 계산하면 서버와 다른 답을 말하게 된다.
    expect(result.lines[0].outcome).toBe('PROMOTED');
    expect(result.guarded).toBe(1);
  });

  it('관리자 지정은 사유를 함께 보내고 지정자는 보내지 않는다', async () => {
    const assignment: TierAssignmentView = {
      sellerId: 7,
      tier: 'STRATEGIC',
      effectiveFrom: '2026-08-22',
      demotionGuardUntil: '2026-11-22',
    };
    vi.mocked(api.post).mockResolvedValueOnce({ data: assignment });

    const result = await sellerTierApi.override(7, 'STRATEGIC', '전략 제휴 체결');

    // 지정자는 서버가 인증 주체에서 딴다 — 본문으로 보내면 위조 가능한 값이 된다.
    expect(api.post).toHaveBeenCalledWith('/admin/seller-tiers/7/override', {
      tier: 'STRATEGIC',
      memo: '전략 제휴 체결',
    });
    expect(result.demotionGuardUntil).toBe('2026-11-22');
  });

  it('정합 검사는 읽기 전용이고 판독 불가 건수를 함께 돌려준다', async () => {
    const integrity: TierIntegrityReport = {
      drifted: 2,
      byKind: { CACHE_STALE: 1, CACHE_MISSING: 1 },
      samples: [
        { sellerId: 1, authoritativeTier: 'VIP', cachedTier: 'NORMAL', kind: 'CACHE_STALE' },
      ],
      unreadable: 1,
    };
    vi.mocked(api.get).mockResolvedValueOnce({ data: integrity });

    const result = await sellerTierApi.integrity(20);

    expect(api.get).toHaveBeenCalledWith('/admin/seller-tiers/integrity', {
      params: { sampleLimit: 20 },
    });
    // unreadable 이 0 이 아니면 그 자체가 조사 대상이다 — 0 으로 뭉개면 안 된다.
    expect(result.unreadable).toBe(1);
    expect(result.samples[0].kind).toBe('CACHE_STALE');
  });

  it('임계 조회는 파라미터가 없다', async () => {
    const policy: SellerTierPolicyView = { vipThreshold: 100_000_000, strategicThreshold: 500_000_000 };
    vi.mocked(api.get).mockResolvedValueOnce({ data: policy });

    const result = await sellerTierApi.policy();

    expect(api.get).toHaveBeenCalledWith('/admin/seller-tiers/policy');
    expect(result.vipThreshold).toBe(100_000_000);
  });

  it('등급 라벨은 세 등급을 모두 덮는다', () => {
    const grades: SellerTierGrade[] = ['NORMAL', 'VIP', 'STRATEGIC'];
    for (const grade of grades) {
      expect(SELLER_TIER_LABEL[grade]).toBeTruthy();
    }
    expect(Object.keys(SELLER_TIER_LABEL)).toHaveLength(3);
  });
});
