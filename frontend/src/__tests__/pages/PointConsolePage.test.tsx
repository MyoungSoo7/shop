import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import PointConsolePage from '@/pages/system/PointConsolePage';
import { pointApi } from '@/api/point';

vi.mock('@/api/point', () => ({
  pointApi: {
    grant: vi.fn(), runExpiry: vi.fn(), myBalance: vi.fn(),
    summary: vi.fn(), account: vi.fn(), policies: vi.fn(), expiring: vi.fn(),
    deduct: vi.fn(), registerPolicy: vi.fn(), closePolicy: vi.fn(),
  },
}));

const mocked = vi.mocked(pointApi);

const openPolicy = {
  id: 1, scope: 'GLOBAL', scopeKey: '-', earnRate: 0.01, validityDays: 365,
  effectiveFrom: '2026-01-01', effectiveTo: null as string | null, reason: '기본 적립률',
  createdBy: 'admin', active: true, closedAt: null as string | null,
};

const closedPolicy = {
  id: 2, scope: 'GLOBAL', scopeKey: '-', earnRate: 0.005, validityDays: 365,
  effectiveFrom: '2025-01-01', effectiveTo: '2026-01-01' as string | null, reason: '구 요율',
  createdBy: 'admin', active: false, closedAt: '2025-12-20T09:00:00Z' as string | null,
};

const balancedSummary = {
  accountCount: 3,
  totalBalance: 12000,
  totalActiveLotRemaining: 12000,
  totalEntryNet: 12000,
  driftedAccountCount: 0,
  expiringWithinDays: 30,
  expiringAmount: 0,
};

describe('PointConsolePage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // 화면 진입 시 현황 4종을 함께 부른다 — 조작 테스트도 이 기본값 위에서 돈다.
    mocked.summary.mockResolvedValue(balancedSummary);
    mocked.policies.mockResolvedValue([]);
    mocked.expiring.mockResolvedValue([]);
  });

  const fillGrantForm = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.type(screen.getByLabelText('회원 ID'), '42');
    await user.type(screen.getByLabelText('지급 포인트'), '5000');
    await user.type(screen.getByLabelText('참조 ID'), 'cs-1');
    await user.type(screen.getByLabelText('지급 사유'), '배송 지연 보상');
  };

  it('사유를 입력하기 전에는 지급 버튼이 잠겨 있다 — 근거 없는 지급을 화면이 먼저 막는다', async () => {
    const user = userEvent.setup();
    render(<PointConsolePage />);

    await user.type(screen.getByLabelText('회원 ID'), '42');
    await user.type(screen.getByLabelText('지급 포인트'), '5000');
    await user.type(screen.getByLabelText('참조 ID'), 'cs-1');

    expect(screen.getByRole('button', { name: '포인트 지급' })).toBeDisabled();

    await user.type(screen.getByLabelText('지급 사유'), '보상');
    expect(screen.getByRole('button', { name: '포인트 지급' })).toBeEnabled();
  });

  it('지급하면 입력한 참조 ID 를 멱등 키로 그대로 보낸다', async () => {
    mocked.grant.mockResolvedValue({
      entryId: 100, lotId: 55, grantedAmount: 5000, remainingBalance: 5000,
    });
    const user = userEvent.setup();
    render(<PointConsolePage />);

    await fillGrantForm(user);
    await user.click(screen.getByRole('button', { name: '포인트 지급' }));

    await waitFor(() => expect(mocked.grant).toHaveBeenCalledWith(expect.objectContaining({
      userId: 42, amount: 5000, referenceId: 'cs-1', reason: '배송 지연 보상',
    })));
  });

  it('멱등 단축 반환(entryId=null)은 중복 지급이 아니었음을 알린다', async () => {
    mocked.grant.mockResolvedValue({
      entryId: null, lotId: null, grantedAmount: 5000, remainingBalance: 5000,
    });
    const user = userEvent.setup();
    render(<PointConsolePage />);

    await fillGrantForm(user);
    await user.click(screen.getByRole('button', { name: '포인트 지급' }));

    expect(await screen.findByRole('status'))
      .toHaveTextContent('이미 지급된 참조 ID');
  });

  it('미리보기를 돌리기 전에는 소멸 실행 버튼이 잠겨 있다', async () => {
    mocked.runExpiry.mockResolvedValue({
      lotCount: 3, accountCount: 2, forfeitedTotal: 1500, dryRun: true,
    });
    const user = userEvent.setup();
    render(<PointConsolePage />);

    expect(screen.getByRole('button', { name: '소멸 실행' })).toBeDisabled();

    await user.click(screen.getByRole('button', { name: '미리보기' }));

    await waitFor(() => expect(screen.getByRole('button', { name: '소멸 실행' })).toBeEnabled());
    expect(mocked.runExpiry).toHaveBeenCalledWith(true);
  });

  it('소멸 실행은 dryRun=false 로 부르고 낡은 미리보기를 지운다', async () => {
    mocked.runExpiry
      .mockResolvedValueOnce({ lotCount: 3, accountCount: 2, forfeitedTotal: 1500, dryRun: true })
      .mockResolvedValueOnce({ lotCount: 3, accountCount: 2, forfeitedTotal: 1500, dryRun: false });
    const user = userEvent.setup();
    render(<PointConsolePage />);

    await user.click(screen.getByRole('button', { name: '미리보기' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '소멸 실행' })).toBeEnabled());
    await user.click(screen.getByRole('button', { name: '소멸 실행' }));

    await waitFor(() => expect(mocked.runExpiry).toHaveBeenLastCalledWith(false));
    expect(await screen.findByText(/소멸 완료/)).toBeInTheDocument();
    // 실행 뒤 미리보기 버튼은 다시 잠긴다 — 낡은 수치로 두 번 실행하지 않게.
    await waitFor(() => expect(screen.getByRole('button', { name: '소멸 실행' })).toBeDisabled());
  });

  it('지급 실패는 서버 문구를 그대로 보여 준다', async () => {
    mocked.grant.mockRejectedValue({ response: { data: { message: '계정이 해지되었습니다' } } });
    const user = userEvent.setup();
    render(<PointConsolePage />);

    await fillGrantForm(user);
    await user.click(screen.getByRole('button', { name: '포인트 지급' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('계정이 해지되었습니다');
  });

  it('지급에 성공하면 위쪽 현황을 다시 읽는다 — 방금 만든 돈이 안 보이면 안 된다', async () => {
    mocked.grant.mockResolvedValue({
      entryId: 100, lotId: 55, grantedAmount: 5000, remainingBalance: 5000,
    });
    const user = userEvent.setup();
    render(<PointConsolePage />);
    await waitFor(() => expect(mocked.summary).toHaveBeenCalledTimes(1));

    await fillGrantForm(user);
    await user.click(screen.getByRole('button', { name: '포인트 지급' }));

    await waitFor(() => expect(mocked.summary).toHaveBeenCalledTimes(2));
  });

  describe('원장 현황', () => {
    it('3자 대조가 맞으면 균형으로 보고한다', async () => {
      render(<PointConsolePage />);

      expect(await screen.findByTestId('point-ledger-balance')).toHaveTextContent('3자 대조 균형');
      expect(screen.queryByTestId('point-ledger-drift')).not.toBeInTheDocument();
    });

    it('어긋난 계정이 있으면 조사 대상으로 지목한다', async () => {
      mocked.summary.mockResolvedValue({ ...balancedSummary, driftedAccountCount: 2 });
      render(<PointConsolePage />);

      expect(await screen.findByTestId('point-ledger-drift')).toHaveTextContent('계정 2개');
    });

    it('소멸 예정 기준 일수를 바꾸면 그 값으로 다시 읽는다', async () => {
      render(<PointConsolePage />);
      await waitFor(() => expect(mocked.summary).toHaveBeenCalledWith(30));

      // 제어 입력이라 한 글자씩 치면 중간 상태(빈 값 → 기본 30)가 값에 섞인다.
      // 여기서 보려는 것은 타이핑이 아니라 "값이 바뀌면 다시 읽는가"이므로 값을 한 번에 넣는다.
      fireEvent.change(screen.getByLabelText('소멸 예정 기준 일수'), { target: { value: '7' } });

      await waitFor(() => expect(mocked.summary).toHaveBeenLastCalledWith(7));
      expect(mocked.expiring).toHaveBeenLastCalledWith(7, 50);
    });
  });

  describe('계정 조회', () => {
    it('조회하면 계정의 3자 대조와 로트·원장 내역을 보여 준다', async () => {
      mocked.account.mockResolvedValue({
        userId: 3, accountId: 70, status: 'ACTIVE',
        available: 12000, locked: 0, total: 12000,
        health: { accountTotal: 12000, activeLotRemaining: 12000, entryNet: 12000 },
        lots: [{
          lotId: 1, origin: 'MANUAL_GRANT', originalAmount: 5000, remainingAmount: 5000,
          status: 'ACTIVE', grantedAt: '2026-08-18T14:31:50Z', expiresAt: '2027-08-18T14:31:50Z',
          referenceType: 'MANUAL', referenceId: 'smoke-1',
        }],
        entries: [{
          entryId: 9, entryType: 'GRANT', amount: 5000, referenceType: 'MANUAL',
          referenceId: 'smoke-1', memo: 'CS 보상', createdBy: 'admin:1',
          createdAt: '2026-08-18T14:31:50Z',
        }],
      });
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await user.type(screen.getByLabelText('조회할 회원 ID'), '3');
      await user.click(screen.getByRole('button', { name: '조회' }));

      expect(await screen.findByTestId('account-health-balanced')).toBeInTheDocument();
      expect(screen.getByText(/MANUAL_GRANT · ACTIVE/)).toBeInTheDocument();
      expect(screen.getByTestId('entry-memo')).toHaveTextContent('CS 보상');
    });

    it('계정 잔고와 로트 합계가 어긋나면 불일치로 표시한다', async () => {
      mocked.account.mockResolvedValue({
        userId: 3, accountId: 70, status: 'ACTIVE',
        available: 1000, locked: 0, total: 1000,
        health: { accountTotal: 1000, activeLotRemaining: 700, entryNet: 1000 },
        lots: [], entries: [],
      });
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await user.type(screen.getByLabelText('조회할 회원 ID'), '3');
      await user.click(screen.getByRole('button', { name: '조회' }));

      expect(await screen.findByTestId('account-health-drift'))
        .toHaveTextContent('로트 합계 700P');
    });

    it('404 는 장애가 아니라 "계정 없음"으로 안내한다 — 잔액 0 인 계정과 다르다', async () => {
      mocked.account.mockRejectedValue({ response: { status: 404 } });
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await user.type(screen.getByLabelText('조회할 회원 ID'), '99');
      await user.click(screen.getByRole('button', { name: '조회' }));

      expect(await screen.findByTestId('point-account-error'))
        .toHaveTextContent('포인트 계정이 아직 없습니다');
    });
  });

  describe('적립률 정책', () => {
    it('정책이 없으면 적립률 0 임을 알린다 — 빈 표는 "설정 안 함"이 아니라 "적립 없음"이다', async () => {
      render(<PointConsolePage />);

      expect(await screen.findByText(/현재 주문 적립은 0P/)).toBeInTheDocument();
    });

    it('종료된 정책도 이력으로 함께 보여 준다', async () => {
      mocked.policies.mockResolvedValue([openPolicy, closedPolicy]);
      render(<PointConsolePage />);

      expect(await screen.findByTestId('policy-active')).toHaveTextContent('적용 중');
      expect(screen.getByTestId('policy-closed')).toHaveTextContent('종료');
    });

    it('종료일이 미래면 그날까지는 적용 중이고 "종료 예약됨"으로 알린다', async () => {
      mocked.policies.mockResolvedValue([{
        ...openPolicy, effectiveTo: '2026-12-01', active: true,
        closedAt: '2026-08-20T09:00:00Z',
      }]);
      render(<PointConsolePage />);

      expect(await screen.findByTestId('policy-close-scheduled'))
        .toHaveTextContent('2026-12-01 까지 적용');
      expect(screen.getByTestId('policy-active')).toHaveTextContent('적용 중');
    });

    it('무기한 정책에만 종료일 지정 버튼이 뜬다 — 이미 끝난 정책은 다시 끊을 수 없다', async () => {
      mocked.policies.mockResolvedValue([openPolicy, closedPolicy]);
      render(<PointConsolePage />);

      await screen.findByTestId('policy-active');
      expect(screen.getAllByRole('button', { name: '종료일 지정' })).toHaveLength(1);
    });

    it('종료를 확정하면 지정한 날짜로 끊고 현황을 다시 읽는다', async () => {
      mocked.policies.mockResolvedValue([openPolicy]);
      mocked.closePolicy.mockResolvedValue({ ...openPolicy, effectiveTo: '2026-12-01' });
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await user.click(await screen.findByRole('button', { name: '종료일 지정' }));
      fireEvent.change(screen.getByLabelText('정책 종료일'), { target: { value: '2026-12-01' } });
      await user.click(screen.getByRole('button', { name: '종료 확정' }));

      await waitFor(() => expect(mocked.closePolicy).toHaveBeenCalledWith(1, '2026-12-01'));
      expect(await screen.findByTestId('policy-notice')).toHaveTextContent('2026-12-01');
      expect(mocked.policies).toHaveBeenCalledTimes(2);
    });

    it('등록은 화면의 % 를 0~1 비율로 바꿔 보낸다 — 변환은 한 곳에서만 한다', async () => {
      mocked.registerPolicy.mockResolvedValue(openPolicy);
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await user.type(await screen.findByLabelText('적립률'), '1.5');
      fireEvent.change(screen.getByLabelText('정책 발효일'), { target: { value: '2026-09-01' } });
      await user.type(screen.getByLabelText('정책 근거'), '하반기 적립률');
      await user.click(screen.getByRole('button', { name: '정책 등록' }));

      await waitFor(() => expect(mocked.registerPolicy).toHaveBeenCalledWith(
        expect.objectContaining({
          scope: 'GLOBAL', scopeKey: '-', earnRate: 0.015,
          effectiveFrom: '2026-09-01', reason: '하반기 적립률',
        }),
      ));
    });

    it('기간이 겹쳐 409 면 서버 문구를 그대로 보여 준다 — 먼저 종료하라는 안내다', async () => {
      mocked.registerPolicy.mockRejectedValue({
        response: { status: 409, data: { message: '현재 정책의 종료일을 먼저 지정하세요' } },
      });
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await user.type(await screen.findByLabelText('적립률'), '2');
      fireEvent.change(screen.getByLabelText('정책 발효일'), { target: { value: '2026-09-01' } });
      await user.type(screen.getByLabelText('정책 근거'), '프로모션');
      await user.click(screen.getByRole('button', { name: '정책 등록' }));

      expect(await screen.findByTestId('policy-error'))
        .toHaveTextContent('현재 정책의 종료일을 먼저 지정하세요');
    });
  });

  describe('수기 차감', () => {
    const fillDeductForm = async (user: ReturnType<typeof userEvent.setup>) => {
      await user.type(screen.getByLabelText('차감 대상 회원 ID'), '3');
      await user.type(screen.getByLabelText('차감 포인트'), '500');
      await user.type(screen.getByLabelText('차감 참조 ID'), 'recall-1');
      await user.type(screen.getByLabelText('차감 사유'), '오지급 회수');
    };

    it('사유를 입력하기 전에는 차감 버튼이 잠겨 있다 — 지급과 같은 규율이다', async () => {
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await user.type(screen.getByLabelText('차감 대상 회원 ID'), '3');
      await user.type(screen.getByLabelText('차감 포인트'), '500');
      await user.type(screen.getByLabelText('차감 참조 ID'), 'recall-1');
      expect(screen.getByRole('button', { name: '포인트 차감' })).toBeDisabled();

      await user.type(screen.getByLabelText('차감 사유'), '오지급 회수');
      expect(screen.getByRole('button', { name: '포인트 차감' })).toBeEnabled();
    });

    it('차감하면 참조 ID 를 멱등 키로 그대로 보내고 현황을 다시 읽는다', async () => {
      mocked.deduct.mockResolvedValue({
        entryId: 9, deductedAmount: 500, remainingBalance: 500,
      });
      const user = userEvent.setup();
      render(<PointConsolePage />);
      await waitFor(() => expect(mocked.summary).toHaveBeenCalledTimes(1));

      await fillDeductForm(user);
      await user.click(screen.getByRole('button', { name: '포인트 차감' }));

      await waitFor(() => expect(mocked.deduct).toHaveBeenCalledWith({
        userId: 3, amount: 500, referenceId: 'recall-1', reason: '오지급 회수',
      }));
      expect(await screen.findByTestId('deduct-result')).toHaveTextContent('차감 완료');
      await waitFor(() => expect(mocked.summary).toHaveBeenCalledTimes(2));
    });

    it('멱등 단축 반환(entryId=null)은 중복 차감이 아니었음을 알린다', async () => {
      mocked.deduct.mockResolvedValue({
        entryId: null, deductedAmount: 500, remainingBalance: 1000,
      });
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await fillDeductForm(user);
      await user.click(screen.getByRole('button', { name: '포인트 차감' }));

      expect(await screen.findByTestId('deduct-result'))
        .toHaveTextContent('이미 차감된 참조 ID');
    });

    it('잔액 부족(422)은 서버 문구를 그대로 보여 준다', async () => {
      mocked.deduct.mockRejectedValue({
        response: { status: 422, data: { message: '차감액이 잔액을 초과합니다' } },
      });
      const user = userEvent.setup();
      render(<PointConsolePage />);

      await fillDeductForm(user);
      await user.click(screen.getByRole('button', { name: '포인트 차감' }));

      expect(await screen.findByTestId('deduct-error'))
        .toHaveTextContent('차감액이 잔액을 초과합니다');
    });
  });

  describe('소멸 예정', () => {
    it('소멸 예정 로트를 회원·금액·만료일로 보여 준다', async () => {
      mocked.expiring.mockResolvedValue([{
        userId: 3, lotId: 1, origin: 'MANUAL_GRANT',
        remainingAmount: 5000, expiresAt: '2027-08-18T14:31:50Z',
      }]);
      render(<PointConsolePage />);

      expect(await screen.findByText(/2027-08-18 만료/)).toBeInTheDocument();
    });
  });
});
