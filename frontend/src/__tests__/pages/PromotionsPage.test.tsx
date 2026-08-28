import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>적립을 "완료"라고 적지 않는다.</b> 포인트 원장은 order-service 에 있고 marketing 은
 * 적립을 <i>요청</i>한다(ADR 0045). {@code rewardPending} 이 참인데 완료라고 적으면 거짓말이다.
 * 레거시는 화면에서 바로 "지급 완료"라고 적었고, 지급이 실패해도 그 문구는 그대로였다.
 *
 * <p>② <b>참여 뒤 판을 서버에서 다시 받는다.</b> 화면이 누적을 +1 해서 고치면 서버의 판정과
 * 갈린다 — 이미 참여한 상태에서 한 번 더 눌렀을 때가 특히 그렇다.
 *
 * <p>③ <b>참여할 수 없는 상태의 버튼은 잠근다.</b> 서버가 거절하겠지만, 오늘 이미 찍은 사람이
 * 한 번 더 눌러 400 을 보는 것과 버튼이 잠겨 있는 것은 다른 화면이다.
 *
 * <p>④ <b>고른 이벤트는 주소에 남는다.</b> 출석은 하루 한 번뿐이라 "이거 지금 열어 봐"라고
 * 보낼 주소가 실제로 필요하다.
 */

vi.mock('@/api/promotion', async () => {
  const actual = await vi.importActual<typeof import('@/api/promotion')>('@/api/promotion');
  return {
    ...actual,
    promotionApi: {
      running: vi.fn(),
      attendanceBoard: vi.fn(),
      checkIn: vi.fn(),
      luckyboxBoard: vi.fn(),
      draw: vi.fn(),
    },
  };
});

const { promotionApi } = await import('@/api/promotion');
const { default: PromotionsPage } = await import('@/pages/PromotionsPage');

const promo = vi.mocked(promotionApi);

const summary = (over: Record<string, unknown> = {}) => ({
  kind: 'ATTENDANCE' as const,
  id: 'a-1',
  name: '9월 출석',
  startsOn: '2026-09-01',
  endsOn: '2026-09-30',
  pcImageUrl: null,
  mobileImageUrl: null,
  ...over,
});

const board = (over: Record<string, unknown> = {}) => ({
  campaignId: 'a-1',
  name: '9월 출석',
  periodType: 'MONTHLY',
  streakRule: 'CONSECUTIVE',
  dayTypeRule: 'EVERY_DAY',
  requiredCount: 3,
  startsOn: '2026-09-01',
  endsOn: '2026-09-30',
  windowStart: '2026-09-01',
  windowEnd: '2026-09-03',
  dailyRewardPoints: '10',
  goalRewardPoints: '100',
  attendedTotal: 1,
  attendedStreak: 1,
  achievedCount: 0,
  checkedInToday: false,
  eligibleToday: true,
  message: '진행 중입니다',
  pcImageUrl: null,
  mobileImageUrl: null,
  days: [
    { date: '2026-09-01', eligible: true, attended: true },
    { date: '2026-09-02', eligible: true, attended: false },
    { date: '2026-09-03', eligible: false, attended: false },
  ],
  ...over,
});

const luckyboxBoard = (over: Record<string, unknown> = {}) => ({
  campaignId: 'l-1',
  name: '가을 럭키박스',
  startsOn: '2026-09-01',
  endsOn: '2026-09-30',
  entryCondition: 'ALL_MEMBERS',
  benefitType: 'IMMEDIATE',
  benefitOn: null,
  note: '하루 한 번',
  drawableNow: true,
  alreadyDrawnInSlot: false,
  pcImageUrl: null,
  mobileImageUrl: null,
  prizes: [
    { id: 'p1', prizeType: 'POINT', rewardPoints: '100', textReward: null, displayOrder: 1 },
    { id: 'p2', prizeType: 'NONE', rewardPoints: null, textReward: null, displayOrder: 2 },
  ],
  myDraws: [],
  ...over,
});

const drawResult = (over: Record<string, unknown> = {}) => ({
  drawId: 'd-1',
  prizeType: 'POINT',
  rewardPoints: '100',
  textReward: null,
  drawnOn: '2026-09-02',
  scheduledOn: null,
  rewardPending: true,
  ...over,
});

const renderPage = (entry = '/promotions') =>
  render(
    <MemoryRouter initialEntries={[entry]}>
      <PromotionsPage />
    </MemoryRouter>,
  );

describe('PromotionsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    promo.running.mockResolvedValue([summary(), summary({ kind: 'LUCKYBOX', id: 'l-1', name: '가을 럭키박스' })]);
    promo.attendanceBoard.mockResolvedValue(board());
    promo.luckyboxBoard.mockResolvedValue(luckyboxBoard());
  });

  it('진행 중인 이벤트가 없으면 그렇게 적는다', async () => {
    promo.running.mockResolvedValue([]);

    renderPage();

    expect(await screen.findByTestId('promotions-empty')).toBeInTheDocument();
    expect(promo.attendanceBoard).not.toHaveBeenCalled();
  });

  it('목록을 못 불러오면 사유를 알린다', async () => {
    promo.running.mockRejectedValue(new Error('boom'));

    renderPage();

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('고르기 전에는 판을 부르지 않는다', async () => {
    renderPage();

    expect(await screen.findByTestId('promotion-no-selection')).toBeInTheDocument();
    expect(promo.attendanceBoard).not.toHaveBeenCalled();
    expect(promo.luckyboxBoard).not.toHaveBeenCalled();
  });

  it('고른 이벤트는 주소에 남고, 그 종류의 판만 부른다', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByTestId('promotion-a-1'));

    await waitFor(() => expect(promo.attendanceBoard).toHaveBeenCalledWith('a-1'));
    expect(promo.luckyboxBoard).not.toHaveBeenCalled();
    expect(await screen.findByTestId('attendance-board')).toBeInTheDocument();
  });

  it('주소에 실려 들어오면 그 이벤트가 바로 열린다', async () => {
    renderPage('/promotions?promotion=l-1');

    expect(await screen.findByTestId('luckybox-board')).toBeInTheDocument();
    expect(promo.luckyboxBoard).toHaveBeenCalledWith('l-1');
  });

  it('판을 못 불러오면 사유를 알리고 판은 비운다', async () => {
    promo.attendanceBoard.mockRejectedValue(new Error('nope'));

    renderPage('/promotions?promotion=a-1');

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByTestId('attendance-board')).not.toBeInTheDocument();
  });

  it('출석 인정일이 아닌 날은 달력에서 구분되고 버튼이 잠긴다', async () => {
    promo.attendanceBoard.mockResolvedValue(board({ eligibleToday: false }));

    renderPage('/promotions?promotion=a-1');

    const button = await screen.findByTestId('attendance-check-in');
    expect(button).toBeDisabled();
    expect(button).toHaveTextContent('출석 인정일이 아닙니다');
    expect(screen.getByTestId('attendance-day-2026-09-03'))
      .toHaveAttribute('aria-label', '2026-09-03 인정 안 함');
  });

  it('오늘 이미 찍었으면 다시 누를 수 없다', async () => {
    promo.attendanceBoard.mockResolvedValue(board({ checkedInToday: true }));

    renderPage('/promotions?promotion=a-1');

    expect(await screen.findByTestId('attendance-check-in')).toBeDisabled();
  });

  it('출석하면 판을 서버에서 다시 받고, 적립은 "완료"가 아니라 "반영 예정"으로 적는다', async () => {
    const user = userEvent.setup();
    promo.checkIn.mockResolvedValue({
      attendedOn: '2026-09-02',
      dailyRewardPoints: '10',
      attendedTotal: 2,
      attendedStreak: 2,
      goalReached: true,
      goalRewardPoints: '100',
      rewardPending: true,
    });

    renderPage('/promotions?promotion=a-1');
    await user.click(await screen.findByTestId('attendance-check-in'));

    const result = await screen.findByTestId('check-in-result');
    expect(result).toHaveTextContent('누적 2일');
    expect(result).toHaveTextContent('잠시 뒤 잔액에 반영');
    expect(result).not.toHaveTextContent('완료');
    expect(screen.getByTestId('check-in-goal')).toHaveTextContent('100P');
    // 화면이 계산해 고치지 않고 서버에 다시 묻는다.
    await waitFor(() => expect(promo.attendanceBoard).toHaveBeenCalledTimes(2));
  });

  it('출석이 거절되면 사유를 알린다', async () => {
    const user = userEvent.setup();
    promo.checkIn.mockRejectedValue(new Error('already'));

    renderPage('/promotions?promotion=a-1');
    await user.click(await screen.findByTestId('attendance-check-in'));

    expect(await screen.findByTestId('action-error')).toBeInTheDocument();
  });

  it('이번 회차에 이미 뽑았으면 뽑기가 잠긴다', async () => {
    promo.luckyboxBoard.mockResolvedValue(luckyboxBoard({ alreadyDrawnInSlot: true }));

    renderPage('/promotions?promotion=l-1');

    expect(await screen.findByTestId('luckybox-draw')).toBeDisabled();
  });

  it('기간 밖이면 뽑기가 잠긴다', async () => {
    promo.luckyboxBoard.mockResolvedValue(luckyboxBoard({ drawableNow: false }));

    renderPage('/promotions?promotion=l-1');

    expect(await screen.findByTestId('luckybox-draw')).toHaveTextContent('지금은 참여할 수 없습니다');
  });

  it('꽝은 꽝이라고 적는다 — 보상도 문구도 없는 경품이 빈칸으로 보이면 오작동처럼 읽힌다', async () => {
    renderPage('/promotions?promotion=l-1');

    expect(await screen.findByTestId('luckybox-prizes')).toHaveTextContent('꽝');
  });

  it('뽑으면 결과를 적고 판을 다시 받는다', async () => {
    const user = userEvent.setup();
    promo.draw.mockResolvedValue(drawResult());

    renderPage('/promotions?promotion=l-1');
    await user.click(await screen.findByTestId('luckybox-draw'));

    expect(await screen.findByTestId('draw-result')).toHaveTextContent('100P 당첨');
    expect(screen.getByTestId('draw-result')).toHaveTextContent('잠시 뒤 잔액에 반영');
    await waitFor(() => expect(promo.luckyboxBoard).toHaveBeenCalledTimes(2));
  });

  it('일괄 지급 캠페인은 지급 예정일을 적는다 — 안 적으면 "안 들어왔다" 문의가 된다', async () => {
    const user = userEvent.setup();
    promo.draw.mockResolvedValue(drawResult({ scheduledOn: '2026-10-01' }));
    promo.luckyboxBoard.mockResolvedValue(luckyboxBoard({
      myDraws: [drawResult({ textReward: '커피 쿠폰', rewardPoints: null })],
    }));

    renderPage('/promotions?promotion=l-1');
    await user.click(await screen.findByTestId('luckybox-draw'));

    expect(await screen.findByTestId('draw-scheduled')).toHaveTextContent('2026-10-01');
    expect(screen.getByTestId('luckybox-my-draws')).toHaveTextContent('커피 쿠폰');
  });

  it('뽑기가 거절되면 사유를 알린다', async () => {
    const user = userEvent.setup();
    promo.draw.mockRejectedValue(new Error('sold out'));

    renderPage('/promotions?promotion=l-1');
    await user.click(await screen.findByTestId('luckybox-draw'));

    expect(await screen.findByTestId('action-error')).toBeInTheDocument();
  });
});
