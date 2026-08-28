import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>등록은 여는 것이 아니다.</b> 만들면 작성 중(DRAFT)으로 끝나고 여는 버튼은 따로다.
 * 레거시는 등록하는 순간 노출됐다 — 문구를 다듬는 동안 이미 구매자가 보고 있었다.
 *
 * <p>② <b>뽑을 경품이 없는 럭키박스는 열 수 없다.</b> 레거시에서는 열렸고, 참여하면 아무 일도
 * 일어나지 않은 채 참여 횟수만 소진됐다.
 *
 * <p>③ <b>경품은 지우지 않고 끈다.</b> 이미 당첨된 사람의 기록이 그 경품을 참조한다.
 *
 * <p>④ <b>경품은 고른 캠페인의 것만 부른다.</b> 목록을 그릴 때 전부 미리 부르면 캠페인 수만큼
 * 왕복이 생기고, 그중 대부분은 아무도 안 여는 캠페인이다.
 */

vi.mock('@/api/promotion', async () => {
  const actual = await vi.importActual<typeof import('@/api/promotion')>('@/api/promotion');
  return {
    ...actual,
    promotionAdminApi: {
      listAttendance: vi.fn(),
      createAttendance: vi.fn(),
      updateAttendance: vi.fn(),
      openAttendance: vi.fn(),
      closeAttendance: vi.fn(),
      listLuckybox: vi.fn(),
      createLuckybox: vi.fn(),
      updateLuckybox: vi.fn(),
      openLuckybox: vi.fn(),
      closeLuckybox: vi.fn(),
      prizes: vi.fn(),
      addPrize: vi.fn(),
      deactivatePrize: vi.fn(),
    },
  };
});

const { promotionAdminApi } = await import('@/api/promotion');
const { default: PromotionAdminPage } = await import('@/pages/PromotionAdminPage');

const admin = vi.mocked(promotionAdminApi);

const attendanceCampaign = (over: Record<string, unknown> = {}) => ({
  id: 'a-1',
  name: '9월 출석',
  status: 'DRAFT',
  periodType: 'MONTHLY',
  startsOn: '2026-09-01',
  endsOn: '2026-09-30',
  streakRule: 'CONSECUTIVE',
  requiredCount: 3,
  dayTypeRule: 'EVERY_DAY',
  dailyRewardPoints: '10',
  goalRewardPoints: '100',
  rewardExpiresFrom: null,
  rewardExpiresOn: null,
  pcImageUrl: null,
  mobileImageUrl: null,
  createdBy: 'admin',
  updatedBy: null,
  ...over,
});

const luckyboxCampaign = (over: Record<string, unknown> = {}) => ({
  id: 'l-1',
  name: '가을 럭키박스',
  status: 'DRAFT',
  startsOn: '2026-09-01',
  endsOn: '2026-09-30',
  benefitType: 'IMMEDIATE',
  benefitOn: null,
  entryCondition: 'PER_DAY',
  rewardExpiresOn: null,
  note: null,
  pcImageUrl: null,
  mobileImageUrl: null,
  createdBy: 'admin',
  updatedBy: null,
  ...over,
});

const prize = (over: Record<string, unknown> = {}) => ({
  id: 'p-1',
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

describe('PromotionAdminPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    admin.listAttendance.mockResolvedValue([attendanceCampaign()]);
    admin.listLuckybox.mockResolvedValue([luckyboxCampaign()]);
    admin.prizes.mockResolvedValue([prize()]);
  });

  it('아무것도 없으면 빈 상태를 적는다', async () => {
    admin.listAttendance.mockResolvedValue([]);
    admin.listLuckybox.mockResolvedValue([]);

    render(<PromotionAdminPage />);

    expect(await screen.findByTestId('attendance-empty')).toBeInTheDocument();
    expect(screen.getByTestId('luckybox-empty')).toBeInTheDocument();
  });

  it('목록을 못 불러오면 사유를 알린다', async () => {
    admin.listAttendance.mockRejectedValue(new Error('boom'));

    render(<PromotionAdminPage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('상태는 한국어로 옮기고, 모르는 값은 그대로 둔다', async () => {
    admin.listAttendance.mockResolvedValue([
      attendanceCampaign(),
      attendanceCampaign({ id: 'a-2', status: 'ARCHIVED' }),
    ]);

    render(<PromotionAdminPage />);

    expect(await screen.findByTestId('attendance-row-a-1')).toHaveTextContent('작성 중');
    expect(screen.getByTestId('attendance-row-a-2')).toHaveTextContent('ARCHIVED');
  });

  it('작성 중에는 열기만, 진행 중에는 닫기만 보인다', async () => {
    admin.listAttendance.mockResolvedValue([
      attendanceCampaign(),
      attendanceCampaign({ id: 'a-2', status: 'RUNNING' }),
    ]);

    render(<PromotionAdminPage />);

    expect(await screen.findByTestId('attendance-open-a-1')).toBeInTheDocument();
    expect(screen.queryByTestId('attendance-close-a-1')).not.toBeInTheDocument();
    expect(screen.getByTestId('attendance-close-a-2')).toBeInTheDocument();
    expect(screen.queryByTestId('attendance-open-a-2')).not.toBeInTheDocument();
  });

  it('열고 닫으면 목록을 다시 받는다 — 상태를 화면이 고치면 서버 판정과 갈린다', async () => {
    const user = userEvent.setup();
    admin.openAttendance.mockResolvedValue(undefined);

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('attendance-open-a-1'));

    await waitFor(() => expect(admin.listAttendance).toHaveBeenCalledTimes(2));
    expect(await screen.findByTestId('promotion-admin-notice')).toHaveTextContent('열었습니다');
  });

  it('여는 데 실패하면 사유를 알린다', async () => {
    const user = userEvent.setup();
    admin.openAttendance.mockRejectedValue(new Error('nope'));

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('attendance-open-a-1'));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('출석 캠페인 등록은 폼 값을 그대로 보내고, 안내는 "아직 작성 중"이라고 적는다', async () => {
    const user = userEvent.setup();
    admin.createAttendance.mockResolvedValue('new-1');

    render(<PromotionAdminPage />);
    await user.type(await screen.findByLabelText('출석 캠페인 이름'), '10월 출석');
    await user.clear(screen.getByLabelText('출석 달성 일수'));
    await user.type(screen.getByLabelText('출석 달성 일수'), '7');
    await user.type(screen.getByLabelText('하루 보상'), '20');
    await user.click(screen.getByTestId('attendance-create'));

    await waitFor(() => expect(admin.createAttendance).toHaveBeenCalled());
    expect(admin.createAttendance.mock.calls[0][0]).toMatchObject({
      name: '10월 출석', requiredCount: 7, dailyRewardPoints: '20',
    });
    expect(await screen.findByTestId('promotion-admin-notice')).toHaveTextContent('작성 중');
  });

  it('비운 보상은 빈 문자열이 아니라 null 로 나간다', async () => {
    const user = userEvent.setup();
    admin.createAttendance.mockResolvedValue('new-1');

    render(<PromotionAdminPage />);
    await user.type(await screen.findByLabelText('달성 보상'), '50');
    await user.clear(screen.getByLabelText('달성 보상'));
    await user.click(screen.getByTestId('attendance-create'));

    await waitFor(() => expect(admin.createAttendance).toHaveBeenCalled());
    expect(admin.createAttendance.mock.calls[0][0].goalRewardPoints).toBeNull();
  });

  it('럭키박스 등록은 참여 횟수 제한까지 실어 보낸다', async () => {
    const user = userEvent.setup();
    admin.createLuckybox.mockResolvedValue('new-2');

    render(<PromotionAdminPage />);
    await user.type(await screen.findByLabelText('럭키박스 캠페인 이름'), '겨울 박스');
    await user.selectOptions(screen.getByLabelText('참여 횟수'), 'PER_PERIOD');
    await user.type(screen.getByLabelText('럭키박스 시작일'), '2026-12-01');
    await user.click(screen.getByTestId('luckybox-create'));

    await waitFor(() => expect(admin.createLuckybox).toHaveBeenCalled());
    expect(admin.createLuckybox.mock.calls[0][0]).toMatchObject({
      name: '겨울 박스', entryCondition: 'PER_PERIOD', startsOn: '2026-12-01',
    });
  });

  /**
   * 이 목록은 marketing-service 의 `EntryCondition` 열거형과 글자까지 같아야 한다.
   *
   * 어긋나 있어도 이 화면의 테스트는 초록이었다 — `createLuckybox` 가 목이라 값이 무엇이든
   * 통과했기 때문이다. 실제로 이 select 는 `ALL_MEMBERS`/`NEW_MEMBER`/`PURCHASER` 를 보내고
   * 있었고(레거시의 참여 *대상* 코드), 백엔드의 같은 이름 필드는 참여 *빈도* 라 어느 값도
   * 역직렬화되지 않았다. 그래서 이 화면으로는 럭키박스 캠페인을 하나도 만들 수 없었다.
   * 목을 쓰는 한 값 자체를 고정하는 수밖에 없다.
   */
  it('참여 횟수 선택지는 백엔드 EntryCondition 과 같은 값만 쓴다', async () => {
    render(<PromotionAdminPage />);

    const select = await screen.findByLabelText('참여 횟수');
    const values = Array.from(select.querySelectorAll('option')).map((o) => o.value);
    expect(values).toEqual(['PER_DAY', 'PER_PERIOD']);
  });

  it('경품은 고르기 전에는 부르지 않는다', async () => {
    render(<PromotionAdminPage />);

    await screen.findByTestId('luckybox-row-l-1');
    expect(admin.prizes).not.toHaveBeenCalled();
    expect(screen.queryByTestId('prize-panel')).not.toBeInTheDocument();
  });

  it('경품을 고르면 그 캠페인의 것만 부른다', async () => {
    const user = userEvent.setup();

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('luckybox-select-l-1'));

    await waitFor(() => expect(admin.prizes).toHaveBeenCalledWith('l-1'));
    expect(await screen.findByTestId('prize-list')).toHaveTextContent('가중치 1');
  });

  it('경품을 못 불러오면 사유를 알린다', async () => {
    const user = userEvent.setup();
    admin.prizes.mockRejectedValue(new Error('boom'));

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('luckybox-select-l-1'));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('뽑을 경품이 없으면 열기 버튼이 잠기고 이유를 적는다', async () => {
    const user = userEvent.setup();
    admin.prizes.mockResolvedValue([prize({ active: false })]);

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('luckybox-select-l-1'));

    expect(await screen.findByTestId('prize-not-ready')).toBeInTheDocument();
    expect(screen.getByTestId('luckybox-open-l-1')).toBeDisabled();
  });

  it('수량이 다 나간 경품만 있어도 열 수 없다', async () => {
    const user = userEvent.setup();
    admin.prizes.mockResolvedValue([prize({ totalQuota: 5, issuedCount: 5 })]);

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('luckybox-select-l-1'));

    expect(await screen.findByTestId('prize-not-ready')).toBeInTheDocument();
  });

  it('경품이 준비되면 열 수 있다', async () => {
    const user = userEvent.setup();
    admin.openLuckybox.mockResolvedValue(undefined);

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('luckybox-select-l-1'));
    await waitFor(() => expect(screen.getByTestId('luckybox-open-l-1')).toBeEnabled());
    await user.click(screen.getByTestId('luckybox-open-l-1'));

    await waitFor(() => expect(admin.openLuckybox).toHaveBeenCalledWith('l-1'));
  });

  it('진행 중 럭키박스는 닫을 수 있다', async () => {
    const user = userEvent.setup();
    admin.listLuckybox.mockResolvedValue([luckyboxCampaign({ status: 'RUNNING' })]);
    admin.closeLuckybox.mockResolvedValue(undefined);

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('luckybox-close-l-1'));

    await waitFor(() => expect(admin.closeLuckybox).toHaveBeenCalledWith('l-1'));
  });

  it('경품을 추가하면 그 캠페인의 경품을 다시 받는다', async () => {
    const user = userEvent.setup();
    admin.addPrize.mockResolvedValue('p-2');

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('luckybox-select-l-1'));
    await screen.findByTestId('prize-form');
    await user.clear(screen.getByLabelText('경품 가중치'));
    await user.type(screen.getByLabelText('경품 가중치'), '3');
    await user.type(screen.getByLabelText('경품 총 수량'), '50');
    await user.click(screen.getByTestId('prize-add'));

    await waitFor(() => expect(admin.addPrize).toHaveBeenCalled());
    expect(admin.addPrize.mock.calls[0][0]).toBe('l-1');
    expect(admin.addPrize.mock.calls[0][1]).toMatchObject({ winRate: '3', totalQuota: 50 });
    expect(admin.prizes).toHaveBeenCalledTimes(2);
  });

  it('총 수량을 비우면 무제한(null)으로 나간다', async () => {
    const user = userEvent.setup();
    admin.addPrize.mockResolvedValue('p-2');

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('luckybox-select-l-1'));
    await screen.findByTestId('prize-form');
    await user.type(screen.getByLabelText('경품 총 수량'), '9');
    await user.clear(screen.getByLabelText('경품 총 수량'));
    await user.clear(screen.getByLabelText('경품 보상 포인트'));
    await user.click(screen.getByTestId('prize-add'));

    await waitFor(() => expect(admin.addPrize).toHaveBeenCalled());
    expect(admin.addPrize.mock.calls[0][1]).toMatchObject({ totalQuota: null, rewardPoints: null });
  });

  it('경품은 지우지 않고 끈다 — 지난 당첨 기록이 그것을 참조한다', async () => {
    const user = userEvent.setup();
    admin.deactivatePrize.mockResolvedValue(undefined);

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('luckybox-select-l-1'));
    await user.click(await screen.findByTestId('prize-deactivate-p-1'));

    await waitFor(() => expect(admin.deactivatePrize).toHaveBeenCalledWith('p-1'));
    expect(await screen.findByTestId('promotion-admin-notice')).toHaveTextContent('기록은 그대로');
  });

  it('꺼진 경품에는 끄기 버튼이 없고, 텍스트 경품은 문구로 보인다', async () => {
    const user = userEvent.setup();
    admin.prizes.mockResolvedValue([
      prize({ id: 'p-9', active: false, rewardPoints: null, textReward: '커피 쿠폰', totalQuota: 10, issuedCount: 2 }),
      prize({ id: 'p-8', rewardPoints: null, textReward: null }),
    ]);

    render(<PromotionAdminPage />);
    await user.click(await screen.findByTestId('luckybox-select-l-1'));

    const list = await screen.findByTestId('prize-list');
    expect(list).toHaveTextContent('커피 쿠폰');
    expect(list).toHaveTextContent('지급 2 / 10');
    expect(list).toHaveTextContent('꽝');
    expect(screen.queryByTestId('prize-deactivate-p-9')).not.toBeInTheDocument();
  });
});
