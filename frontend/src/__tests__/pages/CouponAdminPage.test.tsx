import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import CouponAdminPage from '@/pages/system/CouponAdminPage';
import { couponAdminApi, type CouponPage, type CouponRow } from '@/api/couponAdmin';
import { saveBlob } from '@/api/auditLog';

vi.mock('@/api/couponAdmin', () => ({
  couponAdminApi: {
    search: vi.fn(),
    lifecycleCounts: vi.fn(),
    enums: vi.fn(),
    usages: vi.fn(),
    activate: vi.fn(),
    deactivate: vi.fn(),
    export: vi.fn(),
  },
}));
vi.mock('@/api/auditLog', () => ({ saveBlob: vi.fn() }));

const mocked = vi.mocked(couponAdminApi);
const mockedSave = vi.mocked(saveBlob);

const coupon = (overrides: Partial<CouponRow> = {}): CouponRow => ({
  id: 1,
  code: 'WELCOME10',
  type: 'PERCENTAGE',
  discountValue: 10,
  minOrderAmount: 0,
  maxDiscountAmount: null,
  maxUses: 100,
  usedCount: 3,
  targetType: 'ALL',
  targetId: null,
  startsAt: null,
  expiresAt: '2027-01-01T00:00:00',
  active: true,
  lifecycle: 'ACTIVE',
  createdAt: '2026-01-01T00:00:00',
  ...overrides,
});

const pageOf = (rows: CouponRow[]): CouponPage => ({
  content: rows,
  page: 0,
  size: 50,
  totalElements: rows.length,
  totalPages: rows.length === 0 ? 0 : 1,
});

describe('CouponAdminPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.enums.mockResolvedValue({
      lifecycles: ['ACTIVE', 'SCHEDULED', 'EXPIRED', 'EXHAUSTED', 'INACTIVE'],
      types: ['FIXED', 'PERCENTAGE'],
    });
    mocked.lifecycleCounts.mockResolvedValue([]);
    mocked.search.mockResolvedValue(pageOf([]));
  });

  it('한도 0 은 "무제한"으로 적는다 — "발급 불가"로 읽히면 살아 있는 쿠폰을 죽은 것으로 오해한다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon({ maxUses: 0, usedCount: 12 })]));
    render(<CouponAdminPage />);

    const table = await screen.findByRole('table');
    expect(within(table).getByText(/12 \/ 무제한/)).toBeInTheDocument();
  });

  it('활성 쿠폰에는 "중단", 비활성에는 "재개"가 보인다 — "삭제"는 없다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    render(<CouponAdminPage />);

    expect(await screen.findByRole('button', { name: '중단' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '삭제' })).not.toBeInTheDocument();
  });

  it('비활성 쿠폰에는 재개 버튼이 보인다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon({ active: false, lifecycle: 'INACTIVE' })]));
    render(<CouponAdminPage />);

    expect(await screen.findByRole('button', { name: '재개' })).toBeInTheDocument();
  });

  it('중단하면 코드로 API 를 부르고 목록을 다시 읽는다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.deactivate.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<CouponAdminPage />);
    await screen.findByRole('button', { name: '중단' });

    const before = mocked.search.mock.calls.length;
    await user.click(screen.getByRole('button', { name: '중단' }));

    await waitFor(() => expect(mocked.deactivate).toHaveBeenCalledWith('WELCOME10'));
    await waitFor(() => expect(mocked.search.mock.calls.length).toBeGreaterThan(before));
  });

  it('중단 안내는 즉시 사용 불가라는 사실을 말한다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.deactivate.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '중단' }));

    expect(await screen.findByRole('status')).toHaveTextContent('사용할 수 없습니다');
  });

  it('사용 내역을 펼치면 회수된 이력을 사유와 함께 보여 준다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.usages.mockResolvedValue([
      {
        id: 1, userId: 5, userEmail: 'a@b.c', orderId: 77,
        usedAt: '2026-03-01T10:00:00',
        revokedAt: '2026-03-02T10:00:00', revokeReason: '주문 취소',
      },
    ]);
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '사용 내역' }));

    expect(await screen.findByText(/회수됨: 주문 취소/)).toBeInTheDocument();
  });

  it('사용 이력이 없으면 그렇게 말한다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.usages.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '사용 내역' }));

    expect(await screen.findByText('아직 사용된 적이 없습니다.')).toBeInTheDocument();
  });

  it('상태를 골라도 집계 질의에는 상태를 싣지 않는다', async () => {
    const user = userEvent.setup();
    render(<CouponAdminPage />);
    await waitFor(() => expect(mocked.lifecycleCounts).toHaveBeenCalled());

    await user.selectOptions(await screen.findByLabelText('상태'), 'EXPIRED');

    await waitFor(() =>
      expect(mocked.search.mock.calls.some(([q]) => q.lifecycle === 'EXPIRED')).toBe(true));
    expect(mocked.lifecycleCounts.mock.calls.every(([q]) => q.lifecycle === undefined)).toBe(true);
  });

  it('상태 드롭다운은 서버 enum 으로 그리되 한국어 라벨을 쓴다', async () => {
    render(<CouponAdminPage />);

    expect(await screen.findByRole('option', { name: '한도 소진' })).toBeInTheDocument();
    expect(screen.getByRole('option', { name: '중단됨' })).toBeInTheDocument();
  });

  it('결과가 없으면 그렇게 말한다', async () => {
    render(<CouponAdminPage />);

    expect(await screen.findByText('조건에 맞는 쿠폰이 없습니다.')).toBeInTheDocument();
  });

  it('조회 실패는 사용자에게 드러낸다', async () => {
    mocked.search.mockRejectedValue(new Error('boom'));
    render(<CouponAdminPage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('enum 목록을 못 받아도 화면은 뜬다 — 드롭다운 하나 때문에 조회를 막지 않는다', async () => {
    mocked.enums.mockRejectedValue(new Error('boom'));
    render(<CouponAdminPage />);

    await waitFor(() => expect(mocked.search).toHaveBeenCalled());
    expect(within(await screen.findByLabelText('상태')).getAllByRole('option')).toHaveLength(1);
  });

  it('재개는 activate 를 부르고 기간·한도가 그대로라는 사실을 알린다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon({ active: false, lifecycle: 'INACTIVE' })]));
    mocked.activate.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '재개' }));

    await waitFor(() => expect(mocked.activate).toHaveBeenCalledWith('WELCOME10'));
    expect(await screen.findByRole('status')).toHaveTextContent('기간·한도 조건은 그대로');
  });

  it('중단 실패는 사용자에게 드러난다 — 멈춘 줄 알고 넘어가면 할인이 계속 나간다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.deactivate.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '중단' }));

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('사용 내역을 다시 누르면 접힌다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.usages.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '사용 내역' }));
    await screen.findByText('아직 사용된 적이 없습니다.');

    await user.click(screen.getByRole('button', { name: '사용 내역' }));

    await waitFor(() =>
      expect(screen.queryByText('아직 사용된 적이 없습니다.')).not.toBeInTheDocument());
  });

  it('사용 내역 조회 실패도 드러낸다', async () => {
    mocked.search.mockResolvedValue(pageOf([coupon()]));
    mocked.usages.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '사용 내역' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('사용 내역');
  });

  it('CSV 실패도 드러낸다', async () => {
    mocked.export.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<CouponAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('CSV');
  });

  it('CSV 가 온전하면 전부 받았다고 알린다', async () => {
    mocked.export.mockResolvedValue({
      blob: new Blob(['x']), fileName: 'coupons.csv', truncated: false, total: 9,
    });
    const user = userEvent.setup();
    render(<CouponAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('status')).toHaveTextContent('9장을 모두 내려받았습니다');
  });

  it('상태별 장수 칩을 누르면 그 상태로 좁혀 조회한다', async () => {
    mocked.lifecycleCounts.mockResolvedValue([{ lifecycle: 'EXHAUSTED', count: 4 }]);
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: /한도 소진 4/ }));

    await waitFor(() =>
      expect(mocked.search.mock.calls.some(([q]) => q.lifecycle === 'EXHAUSTED')).toBe(true));
  });

  it('코드·유형·기간 필터는 그대로 질의에 실린다', async () => {
    const user = userEvent.setup();
    render(<CouponAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.type(await screen.findByLabelText('쿠폰 코드'), 'WEL');
    await user.selectOptions(screen.getByLabelText('할인 유형'), 'FIXED');
    await user.type(screen.getByLabelText('생성 시작일'), '2026-01-01');
    await user.type(screen.getByLabelText('생성 종료일'), '2026-01-31');
    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(mocked.search.mock.calls.some(([q]) =>
      q.code === 'WEL' && q.type === 'FIXED'
      && q.from === '2026-01-01' && q.to === '2026-01-31',
    )).toBe(true));
  });

  it('여러 페이지면 이동이 나오고, 조회를 다시 누르면 1페이지로 돌아온다', async () => {
    mocked.search.mockResolvedValue({
      ...pageOf([coupon()]), totalElements: 130, totalPages: 3,
    });
    const user = userEvent.setup();
    render(<CouponAdminPage />);

    await user.click(await screen.findByRole('button', { name: '다음' }));
    await waitFor(() => expect(screen.getByText('2 / 3')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: '이전' }));
    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument());

    await user.click(screen.getByRole('button', { name: '다음' }));
    await waitFor(() => expect(screen.getByText('2 / 3')).toBeInTheDocument());
    await user.click(screen.getByRole('button', { name: '조회' }));
    await waitFor(() => expect(screen.getByText('1 / 3')).toBeInTheDocument());
  });

  it('정액 쿠폰은 원 단위로, 최소 주문액이 0 이면 "없음"으로 적는다', async () => {
    mocked.search.mockResolvedValue(pageOf([
      coupon({ type: 'FIXED', discountValue: 3000, minOrderAmount: 0, startsAt: '2026-02-01T00:00:00', expiresAt: null }),
    ]));
    render(<CouponAdminPage />);

    const table = await screen.findByRole('table');
    expect(within(table).getByText('3,000원')).toBeInTheDocument();
    expect(within(table).getByText('없음')).toBeInTheDocument();
    expect(within(table).getByText(/2026-02-01 ~ 무기한/)).toBeInTheDocument();
  });

  it('CSV 가 잘리면 몇 장 중 몇 장인지 말한다', async () => {
    mocked.export.mockResolvedValue({
      blob: new Blob(['x']), fileName: 'coupons.csv', truncated: true, total: 12345,
    });
    const user = userEvent.setup();
    render(<CouponAdminPage />);
    await waitFor(() => expect(mocked.search).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: 'CSV 내려받기' }));

    expect(await screen.findByRole('status')).toHaveTextContent('12,345장 중 앞 5,000장');
    expect(mockedSave).toHaveBeenCalled();
  });
});
