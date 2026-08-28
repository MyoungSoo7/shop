import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>세 가지 "매출이 안 보인다" 를 뭉개지 않는다.</b> 입점 조직이 아닌 계정(403)·판매
 * 조직이 아닌 법인(매출 개념 없음)·기간 안에 결제가 0 인 판매 조직은 서로 다른 상태다. 셋을
 * 같은 빈 표로 그리면 법인 고객은 자기 데이터가 유실됐다고 읽고, 진짜 권한 사고는 "당신은
 * 입점사가 아닙니다" 라는 안내에 덮여 조사되지 않는다.
 *
 * <p>② <b>403 이라고 다 같은 403 이 아니다.</b> 서버는 인가 실패에도 403 을 준다. 본문의
 * {@code code} 까지 봐야 안내와 사고가 갈린다.
 *
 * <p>③ <b>추정 각주를 숨기지 않는다.</b> 결제시각이 없어 수신 시각으로 집계한 건이 있으면
 * 자정 근처의 하루가 밀린다. 숨기면 "어제 매출이 왜 다르냐" 가 되고 그때는 설명할 근거가 없다.
 *
 * <p>④ <b>실매출 음수를 0 으로 깎지 않는다.</b> 지난달 결제가 이번 달에 환불되면 실제로 음수다.
 * 깎는 순간 화면 합계와 정산액이 어긋나고 아무도 그 차이를 설명하지 못한다.
 */

vi.mock('@/api/partner', async () => {
  const actual = await vi.importActual<typeof import('@/api/partner')>('@/api/partner');
  return {
    ...actual,
    partnerApi: {
      me: vi.fn(),
      members: vi.fn(),
      dashboard: vi.fn(),
      orders: vi.fn(),
      order: vi.fn(),
      exportOrders: vi.fn(),
    },
  };
});

const { partnerApi } = await import('@/api/partner');
const { default: PartnerConsolePage } = await import('@/pages/partner/PartnerConsolePage');

const mock = vi.mocked(partnerApi);

const profile = (over: Record<string, unknown> = {}) => ({
  organizationId: 7,
  organizationName: '가나상사',
  orgType: 'SELLER' as const,
  sellerId: 777,
  myRole: 'OWNER' as const,
  salesAvailable: true,
  currentTier: 'VIP' as const,
  tierEffectiveFrom: '2026-08-01',
  ...over,
});

const dashboard = (over: Record<string, unknown> = {}) => ({
  from: '2026-07-29',
  to: '2026-08-28',
  summary: { grossAmount: '150000', refundedAmount: '20000', netAmount: '130000', orderCount: 12 },
  daily: [{ date: '2026-08-27', grossAmount: '50000', refundedAmount: '0', netAmount: '50000', orderCount: 3 }],
  bestProducts: [{ productId: 11, productName: '텀블러', netAmount: '80000', orderCount: 5 }],
  estimatedCaptureDates: false,
  ...over,
});

const member = (over: Record<string, unknown> = {}) => ({
  membershipId: 1, userId: 42, role: 'OWNER' as const, joinedAt: '2026-06-01T09:00:00', ...over,
});

const httpError = (status: number, data: unknown) =>
  Object.assign(new Error('http'), { isAxiosError: true, response: { status, data } });

const draw = () => render(<MemoryRouter><PartnerConsolePage /></MemoryRouter>);

describe('PartnerConsolePage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mock.members.mockResolvedValue([member()]);
    mock.dashboard.mockResolvedValue(dashboard());
  });

  it('판매 조직은 요약·일자별·인기상품·구성원을 모두 본다', async () => {
    mock.me.mockResolvedValue(profile());

    draw();

    expect(await screen.findByTestId('partner-profile')).toHaveTextContent('가나상사');
    expect(screen.getByTestId('partner-tier')).toHaveTextContent('VIP');
    expect(screen.getByTestId('partner-summary-net')).toHaveTextContent('130,000원');
    expect(screen.getByTestId('partner-order-count')).toHaveTextContent('12건');
    expect(screen.getByTestId('partner-daily')).toHaveTextContent('2026-08-27');
    expect(screen.getByTestId('partner-best')).toHaveTextContent('텀블러');
    expect(screen.getByTestId('partner-members')).toHaveTextContent('회원번호 42');
  });

  /** ①번 상태. 안내이지 사고가 아니다 — 그래서 빨간 오류 박스가 아니라 설명 화면이다. */
  it('입점 조직이 아니면 매출 화면 대신 안내를 그린다', async () => {
    mock.me.mockRejectedValue(httpError(403, { code: 'NOT_A_PARTNER' }));

    draw();

    expect(await screen.findByTestId('partner-not-a-partner')).toHaveTextContent('입점 조직에 속해 있지 않습니다');
    expect(screen.queryByTestId('partner-summary')).toBeNull();
    expect(screen.queryByTestId('partner-error')).toBeNull();
  });

  /**
   * ②번 규율. 같은 403 이어도 코드가 다르면 안내로 덮지 않는다 — 덮으면 권한 사고가
   * "당신은 입점사가 아닙니다" 로 보여 아무도 조사하지 않는다.
   */
  it('코드 없는 403 은 안내가 아니라 오류로 그린다', async () => {
    mock.me.mockRejectedValue(httpError(403, { code: 'FORBIDDEN', message: '접근이 거부되었습니다' }));

    draw();

    expect(await screen.findByTestId('partner-error')).toBeInTheDocument();
    expect(screen.queryByTestId('partner-not-a-partner')).toBeNull();
  });

  /** ①번 상태. 빈 표가 아니라 문장이어야 법인 고객이 "데이터가 사라졌다" 로 읽지 않는다. */
  it('법인 고객은 매출을 부르지도 않고, 개념이 없다고 적는다', async () => {
    mock.me.mockResolvedValue(profile({ orgType: 'CORPORATE', sellerId: null, salesAvailable: false, currentTier: null }));

    draw();

    expect(await screen.findByTestId('partner-no-sales')).toHaveTextContent('해당 개념이 없습니다');
    expect(mock.dashboard).not.toHaveBeenCalled();
    expect(screen.queryByTestId('partner-daily-empty')).toBeNull();
    // 등급 미확인은 NORMAL 이 아니다. 기본값으로 채우면 수수료를 그 표시로 짐작하게 된다.
    expect(screen.getByTestId('partner-tier')).toHaveTextContent('아직 확인되지 않았습니다');
  });

  /** ③번 상태. 판매 조직인데 진짜로 0원인 경우 — 비어 있다고 말하되 매출 화면은 유지한다. */
  it('결제가 없는 기간은 빈 표가 아니라 없다고 적는다', async () => {
    mock.me.mockResolvedValue(profile());
    mock.dashboard.mockResolvedValue(dashboard({ daily: [], bestProducts: [] }));

    draw();

    expect(await screen.findByTestId('partner-daily-empty')).toBeInTheDocument();
    expect(screen.getByTestId('partner-best-empty')).toBeInTheDocument();
    expect(screen.getByTestId('partner-summary')).toBeInTheDocument();
  });

  it('추정 집계가 섞인 기간에는 각주가 뜬다', async () => {
    mock.me.mockResolvedValue(profile());
    mock.dashboard.mockResolvedValue(dashboard({ estimatedCaptureDates: true }));

    draw();

    expect(await screen.findByTestId('partner-estimated')).toHaveTextContent('금액은 정확하며');
  });

  it('실매출 음수를 깎지 않는다', async () => {
    mock.me.mockResolvedValue(profile());
    mock.dashboard.mockResolvedValue(dashboard({
      summary: { grossAmount: '0', refundedAmount: '15000', netAmount: '-15000', orderCount: 0 },
    }));

    draw();

    expect(await screen.findByTestId('partner-summary-net')).toHaveTextContent('-15,000원');
  });

  it('기간을 바꾸면 그 기간으로 다시 부른다', async () => {
    mock.me.mockResolvedValue(profile());
    draw();
    await screen.findByTestId('partner-summary');

    await userEvent.clear(screen.getByTestId('partner-from'));
    await userEvent.type(screen.getByTestId('partner-from'), '2026-08-01');

    await waitFor(() => {
      expect(mock.dashboard).toHaveBeenLastCalledWith(expect.objectContaining({ from: '2026-08-01' }));
    });
  });

  /**
   * 이름·이메일이 없는 것은 누락이 아니라 설계다(조직 이벤트가 숫자 userId 만 싣는다).
   * 화면이 그 사실을 적지 않으면 운영자에게 "구성원 정보가 깨졌다" 로 들어온다.
   */
  it('구성원이 없어도 개인정보 미보관 안내는 남는다', async () => {
    mock.me.mockResolvedValue(profile());
    mock.members.mockResolvedValue([]);

    draw();

    expect(await screen.findByTestId('partner-members-empty')).toBeInTheDocument();
    expect(screen.getByText(/이름·연락처는 이 콘솔에 저장하지 않습니다/)).toBeInTheDocument();
  });

  it('불러오는 동안에는 빈 화면이 아니라 진행 표시를 낸다', () => {
    mock.me.mockReturnValue(new Promise(() => {}));

    draw();

    expect(screen.getByTestId('partner-loading')).toBeInTheDocument();
  });
});
