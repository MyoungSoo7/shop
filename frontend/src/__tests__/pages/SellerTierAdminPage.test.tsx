import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SellerTierAdminPage from '@/pages/SellerTierAdminPage';
import { sellerTierApi, type SellerTierRow } from '@/api/sellerTier';

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
 *
 * <p>그 위에 <b>명부</b>가 있다. 처음 이 화면에는 바꾸는 길만 있고 보는 길이 없었다 — 셋 다 버튼을
 * 눌러야 무언가 나왔고, 지정은 셀러 번호를 이미 알아야 쓸 수 있었다. 그래서 명부는 버튼 없이
 * 떠야 하고, 행에서 바로 지정 대상이 잡혀야 한다.
 */

const showToast = vi.fn();
vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast }) }));

vi.mock('@/api/sellerTier', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/sellerTier')>();
  return {
    ...actual,
    sellerTierApi: {
      evaluate: vi.fn(), override: vi.fn(), integrity: vi.fn(), policy: vi.fn(), list: vi.fn(),
    },
  };
});

const mocked = vi.mocked(sellerTierApi);

const rosterRow = (over: Partial<SellerTierRow> = {}): SellerTierRow => ({
  sellerId: 13, email: 'vip@lemuel.co.kr', name: '김셀러', tier: 'VIP', cachedTier: 'VIP',
  effectiveFrom: '2026-08-01', demotionGuardUntil: '2026-11-01', consecutiveMissCount: 0,
  netSales12m: 820000000, productCount: 12, mismatched: false, ...over,
});

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
  mocked.list.mockResolvedValue({ rows: [rosterRow()], total: 1, truncated: false });
});

describe('SellerTierAdminPage — 셀러 명부', () => {
  it('화면에 들어오면 버튼 없이 명부가 뜬다 — "셀러 등급이 안 보인다"의 실체가 여기였다', async () => {
    render(<SellerTierAdminPage />);

    await waitFor(() => expect(screen.getAllByTestId('tier-roster-row')).toHaveLength(1));
    expect(mocked.list).toHaveBeenCalled();
    const row = screen.getByTestId('tier-roster-row');
    expect(row).toHaveTextContent('김셀러');
    expect(row).toHaveTextContent('#13');
    expect(row).toHaveTextContent('VIP');
    expect(row).toHaveTextContent('820,000,000');
  });

  it('아직 산정되지 않은 셀러도 명부에 남는다 — 지정할 대상이 정확히 이들이다', async () => {
    mocked.list.mockResolvedValue({
      rows: [rosterRow({ sellerId: 21, name: null, email: 'new@lemuel.co.kr', tier: null,
        cachedTier: 'NORMAL', effectiveFrom: null, demotionGuardUntil: null, netSales12m: 0 })],
      total: 1, truncated: false,
    });
    render(<SellerTierAdminPage />);

    const row = await screen.findByTestId('tier-roster-row');
    expect(row).toHaveTextContent('new@lemuel.co.kr');
    expect(row).toHaveTextContent('—');
  });

  it('정본과 캐시가 어긋난 행은 표에서 바로 드러난다 — 총계만으로는 누구인지 알 수 없다', async () => {
    mocked.list.mockResolvedValue({
      rows: [rosterRow({ tier: 'VIP', cachedTier: 'NORMAL', mismatched: true })],
      total: 1, truncated: false,
    });
    render(<SellerTierAdminPage />);

    expect(await screen.findByText('캐시 일반')).toBeInTheDocument();
  });

  it('상한에 잘렸으면 전체 셀러 수를 함께 알린다 — 안 보이는 셀러가 있다는 사실이 숨으면 안 된다', async () => {
    mocked.list.mockResolvedValue({ rows: [rosterRow()], total: 57, truncated: true });
    render(<SellerTierAdminPage />);

    expect(await screen.findByText(/셀러 57명 중 1명/)).toBeInTheDocument();
  });

  it('셀러가 없으면 그 사실을 말한다 — 빈 표는 고장과 구분되지 않는다', async () => {
    mocked.list.mockResolvedValue({ rows: [], total: 0, truncated: false });
    render(<SellerTierAdminPage />);

    expect(await screen.findByTestId('tier-roster-empty')).toBeInTheDocument();
  });

  it('명부를 못 불러와도 번호로 지정하는 길은 남는다', async () => {
    mocked.list.mockRejectedValue(new Error('boom'));
    render(<SellerTierAdminPage />);

    await waitFor(() => expect(screen.getByText(/셀러 명부를 불러오지 못했습니다/)).toBeInTheDocument());
    expect(screen.getByLabelText('셀러 ID')).toBeInTheDocument();
  });

  it('행의 등급 지정을 누르면 번호를 적지 않고 그 셀러가 대상이 된다', async () => {
    const user = userEvent.setup();
    mocked.override.mockResolvedValue({
      sellerId: 13, tier: 'VIP', effectiveFrom: '2026-08-29', demotionGuardUntil: '2026-11-29',
    });
    render(<SellerTierAdminPage />);

    await user.click(await screen.findByRole('button', { name: '등급 지정' }));

    // 번호 입력란이 사라지고 고른 셀러가 그대로 보인다 — 숫자만 남으면 엉뚱한 셀러의
    // 수수료율을 바꿔도 화면에서 알아챌 방법이 없다.
    const chip = screen.getByTestId('tier-override-target');
    expect(chip).toHaveTextContent('김셀러');
    expect(chip).toHaveTextContent('현재 VIP');
    expect(screen.queryByLabelText('셀러 ID')).not.toBeInTheDocument();

    await user.type(screen.getByLabelText('변경 사유'), '전략 제휴 계약');
    await user.click(screen.getByRole('button', { name: '지정' }));

    await waitFor(() => expect(mocked.override).toHaveBeenCalledWith(13, 'VIP', '전략 제휴 계약'));
    // 지정 뒤 명부를 다시 읽는다 — 방금 바꾼 등급이 표에 낡은 채 남으면 무엇이 반영됐는지 알 수 없다.
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(2));
  });
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
