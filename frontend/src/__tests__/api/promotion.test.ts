import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  promotionApi,
  promotionAdminApi,
  hasDrawablePrize,
  CAMPAIGN_STATUS_LABEL,
  type LuckyboxPrizeResponse,
} from '@/api/promotion';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const prize = (over: Partial<LuckyboxPrizeResponse> = {}): LuckyboxPrizeResponse => ({
  id: 'p1',
  prizeType: 'POINT',
  rewardPoints: '100',
  textReward: null,
  totalQuota: null,
  dailyQuota: null,
  winRate: '1',
  issuedCount: 0,
  active: true,
  displayOrder: 1,
  ...over,
});

describe('promotionApi', () => {
  beforeEach(() => vi.resetAllMocks());

  /**
   * 회원 식별자를 화면이 실어 보내면 남의 번호로 출석을 찍을 수 있다. 서버가 토큰에서 꺼낸다.
   * 날짜도 마찬가지다 — 보낼 수 있으면 어제로 소급해 찍힌다.
   */
  it('출석 체크는 회원·날짜를 싣지 않는다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { attendedOn: '2026-08-27' } });

    await promotionApi.checkIn();

    expect(api.post).toHaveBeenCalledWith('/api/promotions/attendance/check-in');
    expect(api.post).toHaveBeenCalledTimes(1);
  });

  it('캠페인을 고르면 쿼리로 붙고, 안 고르면 아예 붙지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {} });

    await promotionApi.attendanceBoard('c-1');
    await promotionApi.attendanceBoard(null);

    expect(api.get).toHaveBeenNthCalledWith(1, '/api/promotions/attendance?campaignId=c-1');
    expect(api.get).toHaveBeenNthCalledWith(2, '/api/promotions/attendance');
  });

  it('아이디에 섞인 특수문자는 인코딩된다 — 그대로 붙이면 쿼리가 끊긴다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {} });

    await promotionApi.luckyboxBoard('a&b=c');

    expect(api.get).toHaveBeenCalledWith('/api/promotions/luckybox?campaignId=a%26b%3Dc');
  });

  it('진행 중 목록·뽑기는 각자의 공개 경로를 부른다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });
    vi.mocked(api.post).mockResolvedValue({ data: {} });

    await promotionApi.running();
    await promotionApi.draw('c-9');

    expect(api.get).toHaveBeenCalledWith('/api/promotions');
    expect(api.post).toHaveBeenCalledWith('/api/promotions/luckybox/draw?campaignId=c-9');
  });
});

describe('promotionAdminApi', () => {
  beforeEach(() => vi.resetAllMocks());

  /**
   * 관리 표면은 /admin/promotions/** 다. 구매자 표면(/api/promotions)과 섞이면 초안 캠페인이
   * 그대로 노출된다 — 서버가 막지만, 화면이 그것을 시험하게 둘 이유가 없다.
   */
  it('목록·등록은 관리 경로를 부른다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'new-1' } });

    await promotionAdminApi.listAttendance();
    await promotionAdminApi.listLuckybox();
    const created = await promotionAdminApi.createLuckybox({
      tenantRef: 'default', name: '가을 럭키박스', startsOn: '2026-09-01', endsOn: '2026-09-30',
      benefitType: 'IMMEDIATE', benefitOn: null, entryCondition: 'ALL_MEMBERS',
      memberJoinedFrom: null, rewardExpiresOn: null, amountBasis: null, minOrderAmount: null,
      shippingStatusRequired: null, note: null, pcImageUrl: null, mobileImageUrl: null,
    });

    expect(api.get).toHaveBeenNthCalledWith(1, '/admin/promotions/attendance');
    expect(api.get).toHaveBeenNthCalledWith(2, '/admin/promotions/luckybox');
    expect(created).toBe('new-1');
  });

  it('수정·개시·종료는 아이디를 경로에 넣는다', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: null });
    vi.mocked(api.post).mockResolvedValue({ data: null });

    await promotionAdminApi.updateAttendance('a 1', {
      tenantRef: 'default', name: 'n', periodType: 'MONTHLY', startsOn: '2026-09-01',
      endsOn: '2026-09-30', streakRule: 'CONSECUTIVE', requiredCount: 3, dayTypeRule: 'EVERY_DAY',
      dailyRewardPoints: null, goalRewardPoints: null, rewardExpiresFrom: null,
      rewardExpiresOn: null, pcImageUrl: null, mobileImageUrl: null, messageBeforeStart: null,
      messageRunning: null, messageAchieved: null, messageClosed: null,
    });
    await promotionAdminApi.openAttendance('a1');
    await promotionAdminApi.closeAttendance('a1');
    await promotionAdminApi.openLuckybox('l1');
    await promotionAdminApi.closeLuckybox('l1');

    expect(vi.mocked(api.put).mock.calls[0][0]).toBe('/admin/promotions/attendance/a%201');
    expect(vi.mocked(api.post).mock.calls.map((call) => call[0])).toEqual([
      '/admin/promotions/attendance/a1/open',
      '/admin/promotions/attendance/a1/close',
      '/admin/promotions/luckybox/l1/open',
      '/admin/promotions/luckybox/l1/close',
    ]);
  });

  it('출석 캠페인 등록은 만든 아이디를 돌려준다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'a-9' } });

    const id = await promotionAdminApi.createAttendance({
      tenantRef: 'default', name: '9월 출석', periodType: 'MONTHLY', startsOn: '2026-09-01',
      endsOn: '2026-09-30', streakRule: 'CONSECUTIVE', requiredCount: 3, dayTypeRule: 'EVERY_DAY',
      dailyRewardPoints: '10', goalRewardPoints: '100', rewardExpiresFrom: null,
      rewardExpiresOn: null, pcImageUrl: null, mobileImageUrl: null, messageBeforeStart: null,
      messageRunning: null, messageAchieved: null, messageClosed: null,
    });

    expect(vi.mocked(api.post).mock.calls[0][0]).toBe('/admin/promotions/attendance');
    expect(id).toBe('a-9');
  });

  it('럭키박스 수정은 캠페인 경로로 나간다', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: null });

    await promotionAdminApi.updateLuckybox('l1', {
      tenantRef: 'default', name: 'n', startsOn: '2026-09-01', endsOn: '2026-09-30',
      benefitType: 'IMMEDIATE', benefitOn: null, entryCondition: 'ALL_MEMBERS',
      memberJoinedFrom: null, rewardExpiresOn: null, amountBasis: null, minOrderAmount: null,
      shippingStatusRequired: null, note: null, pcImageUrl: null, mobileImageUrl: null,
    });

    expect(vi.mocked(api.put).mock.calls[0][0]).toBe('/admin/promotions/luckybox/l1');
  });

  it('경품 조회·추가는 캠페인 아래, 비활성화는 경품 아이디로 나간다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [] });
    vi.mocked(api.post).mockResolvedValue({ data: { id: 'prize-1' } });
    vi.mocked(api.delete).mockResolvedValue({ data: null });

    await promotionAdminApi.prizes('l1');
    const id = await promotionAdminApi.addPrize('l1', {
      prizeType: 'POINT', rewardPoints: '100', textReward: null, totalQuota: null,
      dailyQuota: null, winRate: '1', displayOrder: 1,
    });
    await promotionAdminApi.deactivatePrize('prize-1');

    expect(api.get).toHaveBeenCalledWith('/admin/promotions/luckybox/l1/prizes');
    expect(vi.mocked(api.post).mock.calls[0][0]).toBe('/admin/promotions/luckybox/l1/prizes');
    // 지우지 않고 끈다 — 지난 당첨 기록이 이 경품을 참조한다.
    expect(api.delete).toHaveBeenCalledWith('/admin/promotions/luckybox/prizes/prize-1');
    expect(id).toBe('prize-1');
  });
});

describe('hasDrawablePrize', () => {
  /**
   * 레거시는 뽑을 것이 없는 럭키박스도 열렸고, 참여하면 아무 일 없이 참여 횟수만 소진됐다.
   * 이 판정은 서버 불변식의 사본이다 — 폼에서 미리 막아 400 을 덜 보게 한다.
   */
  it('활성·가중치>0·수량 남은 경품이 하나라도 있어야 참이다', () => {
    expect(hasDrawablePrize([])).toBe(false);
    expect(hasDrawablePrize([prize({ active: false })])).toBe(false);
    expect(hasDrawablePrize([prize({ winRate: '0' })])).toBe(false);
    expect(hasDrawablePrize([prize({ totalQuota: 5, issuedCount: 5 })])).toBe(false);
    expect(hasDrawablePrize([prize({ totalQuota: 5, issuedCount: 4 })])).toBe(true);
    expect(hasDrawablePrize([prize({ active: false }), prize({ id: 'p2' })])).toBe(true);
  });

  it('상태 라벨은 알려진 값만 옮긴다', () => {
    expect(CAMPAIGN_STATUS_LABEL.DRAFT).toBe('작성 중');
    expect(CAMPAIGN_STATUS_LABEL.RUNNING).toBe('진행 중');
    expect(CAMPAIGN_STATUS_LABEL.CLOSED).toBe('종료');
  });
});
