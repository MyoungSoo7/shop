import api from './axios';

/**
 * 이벤트 프로모션(marketing-service) API — 출석체크 · 럭키박스.
 *
 * <p><b>회원 식별자를 보내는 자리가 없다.</b> 서버가 토큰에서 꺼낸다. 레거시는 화면이
 * 회원번호를 파라미터로 실어 보냈고, 그러면 남의 번호로 출석을 찍을 수 있다.
 *
 * <p><b>날짜를 보내는 자리도 없다.</b> "오늘" 은 서버가 KST 로 정한다 — 클라이언트가 날짜를
 * 보낼 수 있으면 어제 날짜로 출석을 소급해 찍을 수 있다.
 *
 * <p><b>{@code campaignId} 는 선택이다.</b> 없으면 오늘 진행 중인 것 중 하나를 서버가 고른다.
 * 레거시는 정렬 없는 {@code ROWNUM = 1} 이라 이벤트가 둘 이상이면 새로고침할 때마다 다른
 * 이벤트가 떴다. 지금은 시작일·이름 순으로 결정적이다.
 *
 * <p><b>포인트는 이 서비스가 주지 않는다.</b> marketing 은 order 의 포인트 원장에 적립을
 * *요청*하고 결과를 되받아 확정한다([ADR 0045](docs/adr/0045-marketing-service-extracted-from-legacy.md)).
 * 그래서 응답의 {@code rewardPending} 이 참이면 잔액은 아직 오르지 않았다 — 화면이 "적립
 * 완료"라고 적으면 거짓말이 된다.
 */

export type PromotionKind = 'ATTENDANCE' | 'LUCKYBOX';

export interface PromotionSummary {
  kind: PromotionKind;
  id: string;
  name: string;
  startsOn: string;
  endsOn: string;
  pcImageUrl: string | null;
  mobileImageUrl: string | null;
}

export interface AttendanceDay {
  date: string;
  /** 그날이 출석 인정 요일인가 — 평일만 인정하는 캠페인의 토·일이 false 다. */
  eligible: boolean;
  attended: boolean;
}

export interface AttendanceBoard {
  campaignId: string;
  name: string;
  periodType: string;
  streakRule: string;
  dayTypeRule: string;
  requiredCount: number;
  startsOn: string;
  endsOn: string;
  /** 집계 창 — 월간 캠페인이면 이번 달, 일간이면 캠페인 기간. 달력이 그리는 범위다. */
  windowStart: string;
  windowEnd: string;
  dailyRewardPoints: string | null;
  goalRewardPoints: string | null;
  attendedTotal: number;
  attendedStreak: number;
  achievedCount: number;
  checkedInToday: boolean;
  eligibleToday: boolean;
  message: string | null;
  pcImageUrl: string | null;
  mobileImageUrl: string | null;
  days: AttendanceDay[];
}

export interface CheckInResult {
  attendedOn: string;
  dailyRewardPoints: string | null;
  attendedTotal: number;
  attendedStreak: number;
  goalReached: boolean;
  goalRewardPoints: string | null;
  rewardPending: boolean;
}

export interface LuckyboxPrize {
  id: string;
  prizeType: string;
  rewardPoints: string | null;
  textReward: string | null;
  displayOrder: number;
}

export interface DrawResult {
  drawId: string;
  prizeType: string;
  rewardPoints: string | null;
  textReward: string | null;
  drawnOn: string;
  /** 일괄 지급 캠페인의 지급 예정일. 즉시 지급이면 null 이다. */
  scheduledOn: string | null;
  rewardPending: boolean;
}

export interface LuckyboxBoard {
  campaignId: string;
  name: string;
  startsOn: string;
  endsOn: string;
  entryCondition: string;
  benefitType: string;
  benefitOn: string | null;
  note: string | null;
  drawableNow: boolean;
  alreadyDrawnInSlot: boolean;
  pcImageUrl: string | null;
  mobileImageUrl: string | null;
  prizes: LuckyboxPrize[];
  myDraws: DrawResult[];
}

// 경로는 전체 리터럴로 적는다. 조각을 이어 붙이면 사람 눈에도, 저장소의 화면-API 대조
// 게이트(api-screen-gate)에도 어떤 엔드포인트를 부르는지 보이지 않는다.
const withCampaign = (base: string, campaignId?: string | null): string =>
  (campaignId ? `${base}?campaignId=${encodeURIComponent(campaignId)}` : base);

export const promotionApi = {
  /** GET — 오늘 진행 중인 이벤트. 곧 끝나는 것부터 온다. */
  running: async (): Promise<PromotionSummary[]> =>
    (await api.get<PromotionSummary[]>('/api/promotions')).data,

  attendanceBoard: async (campaignId?: string | null): Promise<AttendanceBoard> =>
    (await api.get<AttendanceBoard>(withCampaign('/api/promotions/attendance', campaignId))).data,

  checkIn: async (campaignId?: string | null): Promise<CheckInResult> =>
    (await api.post<CheckInResult>(withCampaign('/api/promotions/attendance/check-in', campaignId))).data,

  luckyboxBoard: async (campaignId?: string | null): Promise<LuckyboxBoard> =>
    (await api.get<LuckyboxBoard>(withCampaign('/api/promotions/luckybox', campaignId))).data,

  draw: async (campaignId?: string | null): Promise<DrawResult> =>
    (await api.post<DrawResult>(withCampaign('/api/promotions/luckybox/draw', campaignId))).data,
};

// ------------------------------------------------------------------ 운영자

export type CampaignStatus = 'DRAFT' | 'RUNNING' | 'CLOSED';

export const CAMPAIGN_STATUS_LABEL: Record<string, string> = {
  DRAFT: '작성 중',
  RUNNING: '진행 중',
  CLOSED: '종료',
};

export interface AttendanceCampaignRequest {
  tenantRef: string;
  name: string;
  periodType: string;
  startsOn: string;
  endsOn: string;
  streakRule: string;
  requiredCount: number;
  dayTypeRule: string;
  dailyRewardPoints: string | null;
  goalRewardPoints: string | null;
  rewardExpiresFrom: string | null;
  rewardExpiresOn: string | null;
  pcImageUrl: string | null;
  mobileImageUrl: string | null;
  messageBeforeStart: string | null;
  messageRunning: string | null;
  messageAchieved: string | null;
  messageClosed: string | null;
}

export interface AttendanceCampaignResponse {
  id: string;
  name: string;
  status: string;
  periodType: string;
  startsOn: string;
  endsOn: string;
  streakRule: string;
  requiredCount: number;
  dayTypeRule: string;
  dailyRewardPoints: string | null;
  goalRewardPoints: string | null;
  rewardExpiresFrom: string | null;
  rewardExpiresOn: string | null;
  pcImageUrl: string | null;
  mobileImageUrl: string | null;
  createdBy: string | null;
  updatedBy: string | null;
}

export interface LuckyboxCampaignRequest {
  tenantRef: string;
  name: string;
  startsOn: string;
  endsOn: string;
  benefitType: string;
  benefitOn: string | null;
  entryCondition: string;
  memberJoinedFrom: string | null;
  rewardExpiresOn: string | null;
  amountBasis: string | null;
  minOrderAmount: string | null;
  shippingStatusRequired: string | null;
  note: string | null;
  pcImageUrl: string | null;
  mobileImageUrl: string | null;
}

export interface LuckyboxCampaignResponse {
  id: string;
  name: string;
  status: string;
  startsOn: string;
  endsOn: string;
  benefitType: string;
  benefitOn: string | null;
  entryCondition: string;
  rewardExpiresOn: string | null;
  note: string | null;
  pcImageUrl: string | null;
  mobileImageUrl: string | null;
  createdBy: string | null;
  updatedBy: string | null;
}

export interface LuckyboxPrizeRequest {
  prizeType: string;
  rewardPoints: string | null;
  textReward: string | null;
  totalQuota: number | null;
  dailyQuota: number | null;
  winRate: string;
  displayOrder: number;
}

export interface LuckyboxPrizeResponse {
  id: string;
  prizeType: string;
  rewardPoints: string | null;
  textReward: string | null;
  totalQuota: number | null;
  dailyQuota: number | null;
  /** 확률이 아니라 <b>가중치</b>다 — 활성 경품들의 합으로 정규화된다. 합이 1 일 필요가 없다. */
  winRate: string;
  issuedCount: number;
  active: boolean;
  displayOrder: number;
}

export const promotionAdminApi = {
  listAttendance: async (): Promise<AttendanceCampaignResponse[]> =>
    (await api.get<AttendanceCampaignResponse[]>('/admin/promotions/attendance')).data,

  createAttendance: async (body: AttendanceCampaignRequest): Promise<string> =>
    (await api.post<{ id: string }>('/admin/promotions/attendance', body)).data.id,

  updateAttendance: async (campaignId: string, body: AttendanceCampaignRequest): Promise<void> => {
    await api.put(`/admin/promotions/attendance/${encodeURIComponent(campaignId)}`, body);
  },

  openAttendance: async (campaignId: string): Promise<void> => {
    await api.post(`/admin/promotions/attendance/${encodeURIComponent(campaignId)}/open`);
  },

  closeAttendance: async (campaignId: string): Promise<void> => {
    await api.post(`/admin/promotions/attendance/${encodeURIComponent(campaignId)}/close`);
  },

  listLuckybox: async (): Promise<LuckyboxCampaignResponse[]> =>
    (await api.get<LuckyboxCampaignResponse[]>('/admin/promotions/luckybox')).data,

  createLuckybox: async (body: LuckyboxCampaignRequest): Promise<string> =>
    (await api.post<{ id: string }>('/admin/promotions/luckybox', body)).data.id,

  updateLuckybox: async (campaignId: string, body: LuckyboxCampaignRequest): Promise<void> => {
    await api.put(`/admin/promotions/luckybox/${encodeURIComponent(campaignId)}`, body);
  },

  openLuckybox: async (campaignId: string): Promise<void> => {
    await api.post(`/admin/promotions/luckybox/${encodeURIComponent(campaignId)}/open`);
  },

  closeLuckybox: async (campaignId: string): Promise<void> => {
    await api.post(`/admin/promotions/luckybox/${encodeURIComponent(campaignId)}/close`);
  },

  prizes: async (campaignId: string): Promise<LuckyboxPrizeResponse[]> =>
    (await api.get<LuckyboxPrizeResponse[]>(
      `/admin/promotions/luckybox/${encodeURIComponent(campaignId)}/prizes`)).data,

  addPrize: async (campaignId: string, body: LuckyboxPrizeRequest): Promise<string> =>
    (await api.post<{ id: string }>(
      `/admin/promotions/luckybox/${encodeURIComponent(campaignId)}/prizes`, body)).data.id,

  /** 지우지 않고 끈다 — 이미 당첨된 사람의 기록이 이 경품을 참조한다. */
  deactivatePrize: async (prizeId: string): Promise<void> => {
    await api.delete(`/admin/promotions/luckybox/prizes/${encodeURIComponent(prizeId)}`);
  },
};

/**
 * 이 캠페인을 지금 열 수 있는가 — 서버 불변식의 사본이다. 최종 판정은 언제나 서버가 한다.
 *
 * <p>뽑을 경품이 하나도 없는 럭키박스는 열리지 않는다. 레거시는 열렸고, 참여하면 아무 일도
 * 일어나지 않은 채 참여 횟수만 소진됐다. 폼에서 미리 막아 400 을 덜 보게 한다.
 */
export const hasDrawablePrize = (prizes: LuckyboxPrizeResponse[]): boolean =>
  prizes.some((prize) => prize.active && Number(prize.winRate) > 0
    && (prize.totalQuota === null || prize.issuedCount < prize.totalQuota));
