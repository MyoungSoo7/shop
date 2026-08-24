import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SellerTierAdminPage from '@/pages/SellerTierAdminPage';
import { sellerTierApi } from '@/api/sellerTier';

/**
 * 이 콘솔이 지켜야 하는 규율은 <b>되돌릴 수 없는 것 앞에 문턱을 둔다</b>로 요약된다.
 *
 * <p>등급 하나가 수수료율·정산주기·홀드백을 동시에 바꾸고, 그 등급으로 만들어진 정산은
 * 스냅샷이라 나중에 등급을 되돌려도 되돌아오지 않는다. 그래서 ①미리보기를 받기 전에는
 * 반영 버튼이 열리지 않고 ②기준일을 바꾸면 이전 미리보기 결과를 버린다. ②가 없으면
 * "8월 1일을 미리보고 8월 20일을 반영"이 가능해지는데, 그 실수는 반영된 뒤에야 드러난다.
 *
 * <p>지정에 사유를 강제하는 것도 같은 결이다 — 근거 없는 등급 변경이 이력에 쌓이면 감사가
 * 의미를 잃는다. 서버가 @NotBlank 로 막지만 버튼 단계에서 먼저 거른다.
 */

const showToast = vi.fn();
vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

vi.mock('@/api/sellerTier', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/sellerTier')>();
  return {
    ...actual,
    sellerTierApi: {
      evaluate: vi.fn(), override: vi.fn(), integrity: vi.fn(), policy: vi.fn(),
    },
  };
});

const mocked = vi.mocked(sellerTierApi);

const report = (over: Partial<ReturnType<typeof baseReport>> = {}) => ({ ...baseReport(), ...over });
const baseReport = () => ({
  evaluated: 3, promoted: 1, demoted: 1, held: 1, guarded: 0, failed: 0, dryRun: true,
  lines: [
    { sellerId: 7, fromTier: 'NORMAL', toTier: 'VIP', outcome: 'PROMOTED', netSales: 90000000, reason: null },
  ],
});

beforeEach(() => {
  vi.clearAllMocks();
  mocked.policy.mockResolvedValue({ vipThreshold: 50000000, strategicThreshold: 200000000 });
  mocked.evaluate.mockResolvedValue(report());
});

describe('SellerTierAdminPage', () => {
  it('적용 중인 임계를 보여 준다 — 기준을 모르면 재산정 결과를 해석할 수 없다', async () => {
    render(<SellerTierAdminPage />);

    await waitFor(() => expect(screen.getByTestId('vip-threshold')).toBeInTheDocument());
    expect(screen.getByTestId('vip-threshold')).toHaveTextContent('50,000,000');
    expect(screen.getByTestId('strategic-threshold')).toHaveTextContent('200,000,000');
  });

  it('미리보기를 받기 전에는 반영 버튼이 잠겨 있다', async () => {
    render(<SellerTierAdminPage />);

    await waitFor(() => expect(screen.getByTestId('tier-policy')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '반영' })).toBeDisabled();
  });

  it('미리보기는 dryRun=true 로 부르고, 그 뒤에야 반영 버튼이 열린다', async () => {
    const user = userEvent.setup();
    render(<SellerTierAdminPage />);

    await user.click(await screen.findByRole('button', { name: '미리보기' }));

    await waitFor(() => expect(screen.getByTestId('tier-evaluate-result')).toBeInTheDocument());
    expect(mocked.evaluate).toHaveBeenCalledWith(true, expect.any(String));
    expect(screen.getByText(/아직 아무것도 바뀌지 않았습니다/)).toBeInTheDocument();
    // 승급 1 + 강등 1 = 2건이 바뀔 예정이라는 사실이 버튼에 드러난다.
    expect(screen.getByRole('button', { name: '2건 반영' })).toBeEnabled();
  });

  it('기준일을 바꾸면 이전 미리보기를 버려 반영 버튼이 다시 잠긴다', async () => {
    const user = userEvent.setup();
    render(<SellerTierAdminPage />);

    await user.click(await screen.findByRole('button', { name: '미리보기' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '2건 반영' })).toBeEnabled());

    // 다른 날의 판정으로 반영하는 실수는 반영된 뒤에야 드러난다 — 그래서 여기서 끊는다.
    fireChangeDate(screen.getByLabelText('재산정 기준일'), '2026-07-01');

    await waitFor(() => expect(screen.getByRole('button', { name: '반영' })).toBeDisabled());
    expect(screen.queryByTestId('tier-evaluate-result')).not.toBeInTheDocument();
  });

  it('바뀔 게 없는 미리보기에는 반영 버튼을 열지 않는다', async () => {
    const user = userEvent.setup();
    mocked.evaluate.mockResolvedValue(report({ promoted: 0, demoted: 0, held: 3, lines: [] }));
    render(<SellerTierAdminPage />);

    await user.click(await screen.findByRole('button', { name: '미리보기' }));

    await waitFor(() => expect(screen.getByTestId('tier-evaluate-result')).toBeInTheDocument());
    expect(screen.getByRole('button', { name: '0건 반영' })).toBeDisabled();
  });

  it('사유가 비면 등급 지정 버튼이 잠긴다', async () => {
    const user = userEvent.setup();
    render(<SellerTierAdminPage />);

    await user.type(await screen.findByLabelText('셀러 ID'), '7');
    expect(screen.getByRole('button', { name: '지정' })).toBeDisabled();

    await user.type(screen.getByLabelText('변경 사유'), '전략 제휴 계약');
    expect(screen.getByRole('button', { name: '지정' })).toBeEnabled();
  });

  it('등급 지정은 셀러·등급·사유만 보낸다 — 지정자는 서버가 인증 주체에서 딴다', async () => {
    const user = userEvent.setup();
    mocked.override.mockResolvedValue({
      sellerId: 7, tier: 'VIP', effectiveFrom: '2026-08-21', demotionGuardUntil: '2026-11-21',
    });
    render(<SellerTierAdminPage />);

    await user.type(await screen.findByLabelText('셀러 ID'), '7');
    await user.type(screen.getByLabelText('변경 사유'), '전략 제휴 계약');
    await user.click(screen.getByRole('button', { name: '지정' }));

    await waitFor(() => expect(mocked.override).toHaveBeenCalledWith(7, 'VIP', '전략 제휴 계약'));
  });

  it('정합 검사에서 판독 불가 행은 별도로 경고한다 — 그 자체가 조사 대상이다', async () => {
    const user = userEvent.setup();
    mocked.integrity.mockResolvedValue({
      drifted: 1, byKind: { CACHE_STALE: 1 }, unreadable: 2,
      samples: [{ sellerId: 9, authoritativeTier: 'VIP', cachedTier: 'NORMAL', kind: 'CACHE_STALE' }],
    });
    render(<SellerTierAdminPage />);

    await user.click(await screen.findByRole('button', { name: '검사' }));

    await waitFor(() => expect(screen.getByTestId('tier-integrity-result')).toBeInTheDocument());
    expect(screen.getByText(/불일치 1건 · 판독 불가 2건/)).toBeInTheDocument();
    expect(screen.getByText(/알 수 없는 값인 행이 있습니다/)).toBeInTheDocument();
  });
});

/** date input 은 userEvent.type 으로 다루기 번거로워 change 이벤트를 직접 쏜다. */
function fireChangeDate(input: HTMLElement, value: string) {
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
  setter?.call(input, value);
  input.dispatchEvent(new Event('change', { bubbles: true }));
}
