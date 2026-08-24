import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import GiftCardConsolePage from '@/pages/system/GiftCardConsolePage';
import { giftCardApi } from '@/api/giftCard';

vi.mock('@/api/giftCard', () => ({
  giftCardApi: { issue: vi.fn(), runExpiry: vi.fn(), redeem: vi.fn(), myBalance: vi.fn() },
}));

const mocked = vi.mocked(giftCardApi);

const card = (id: number, code: string, faceAmount = 50000) =>
  ({ giftCardId: id, code, codeLast4: code.slice(-4), faceAmount });

/**
 * jsdom 에는 objectURL 도 앵커 내려받기도 없다. 내려받기 "행위"를 관찰할 수 있게 세 지점을
 * 가로채고, 실제로 어떤 Blob 이 만들어졌는지를 붙잡아 내용까지 확인한다.
 */
const captureDownload = () => {
  const captured: { blob: Blob | null; fileName: string | null; revoked: boolean } = {
    blob: null, fileName: null, revoked: false,
  };
  vi.stubGlobal('URL', {
    ...URL,
    createObjectURL: vi.fn((blob: Blob) => {
      captured.blob = blob;
      return 'blob:gift-cards';
    }),
    revokeObjectURL: vi.fn(() => {
      captured.revoked = true;
    }),
  });
  vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
    captured.fileName = this.download;
  });
  return captured;
};

describe('GiftCardConsolePage — 발행 요청', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('메모가 비어 있으면 아예 싣지 않는다 — 빈 문자열 메모가 이력에 남지 않게', async () => {
    mocked.issue.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);

    await user.type(screen.getByLabelText('메모'), '   ');
    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    await waitFor(() => expect(mocked.issue).toHaveBeenCalledWith(
      expect.objectContaining({ memo: undefined })));
  });

  it('메모는 앞뒤 공백을 털어 보낸다', async () => {
    mocked.issue.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);

    await user.type(screen.getByLabelText('메모'), '  8월 프로모션  ');
    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    await waitFor(() => expect(mocked.issue).toHaveBeenCalledWith(
      expect.objectContaining({ memo: '8월 프로모션' })));
  });

  it('권면가·유효기간도 숫자로 실린다', async () => {
    mocked.issue.mockResolvedValue([]);
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);

    await user.clear(screen.getByLabelText('권면가'));
    await user.type(screen.getByLabelText('권면가'), '10000');
    await user.clear(screen.getByLabelText('유효기간'));
    await user.type(screen.getByLabelText('유효기간'), '90');
    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    await waitFor(() => expect(mocked.issue).toHaveBeenCalledWith(
      expect.objectContaining({ faceAmount: 10000, validityDays: 90 })));
  });

  it('발행 실패는 서버 문구를 그대로 보여 준다', async () => {
    mocked.issue.mockRejectedValue({ response: { data: { message: '발행 한도를 넘었습니다' } } });
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);

    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('발행 한도를 넘었습니다');
    expect(screen.queryByRole('button', { name: '코드 CSV 내려받기' })).not.toBeInTheDocument();
  });
});

describe('GiftCardConsolePage — 코드는 지금 아니면 못 받는다', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.issue.mockResolvedValue([
      card(1, 'GC-AAAA1111BBBB2222'),
      card(2, 'GC-CCCC3333DDDD4444', 30000),
    ]);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('CSV 에는 발행 응답에만 있는 코드 원문이 들어간다 — 서버에는 해시만 남는다', async () => {
    const captured = captureDownload();
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);
    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    await user.click(await screen.findByRole('button', { name: '코드 CSV 내려받기' }));

    expect(await captured.blob!.text()).toBe(
      'giftCardId,code,faceAmount\n'
      + '1,GC-AAAA1111BBBB2222,50000\n'
      + '2,GC-CCCC3333DDDD4444,30000',
    );
  });

  it('내려받기는 파일명을 붙이고 objectURL 을 반납한다 — 안 풀면 탭이 살아 있는 동안 새지 않는다', async () => {
    const captured = captureDownload();
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);
    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    await user.click(await screen.findByRole('button', { name: '코드 CSV 내려받기' }));

    expect(captured.fileName).toMatch(/^gift-cards-\d+\.csv$/);
    expect(captured.revoked).toBe(true);
  });

  it('저장하기 전에는 경고가 서 있고, 내려받으면 내려간다', async () => {
    captureDownload();
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);
    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('다시 볼 수 없습니다');

    await user.click(screen.getByRole('button', { name: '코드 CSV 내려받기' }));

    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
  });

  it('다시 발행하면 경고가 되살아난다 — 앞 묶음을 받았다고 새 묶음까지 받은 것은 아니다', async () => {
    captureDownload();
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);
    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));
    await user.click(await screen.findByRole('button', { name: '코드 CSV 내려받기' }));
    await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());

    mocked.issue.mockResolvedValue([card(3, 'GC-EEEE5555FFFF6666')]);
    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('다시 볼 수 없습니다');
  });

  it('발행한 코드는 표에도 그대로 보인다 — 마지막 네 자리로 줄이지 않는다', async () => {
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);

    await user.click(screen.getByRole('button', { name: '기프트카드 발행' }));

    const table = await screen.findByRole('table');
    expect(within(table).getByText('GC-AAAA1111BBBB2222')).toBeInTheDocument();
    expect(within(table).getByText('30,000원')).toBeInTheDocument();
  });
});

describe('GiftCardConsolePage — 소멸은 미리보기를 거쳐야 한다', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('아무것도 하지 않았을 때 왜 잠겼는지 알려 준다', async () => {
    render(<GiftCardConsolePage />);

    expect(await screen.findByText('미리보기를 먼저 실행하면 소멸 버튼이 열립니다.'))
      .toBeInTheDocument();
  });

  it('미리보기는 dryRun 으로 부르고 소멸 예정액을 알려 준다 — 이 호출로는 아무것도 사라지지 않는다', async () => {
    mocked.runExpiry.mockResolvedValue({ cardCount: 2, forfeitedTotal: 30000, dryRun: true });
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);

    await user.click(screen.getByRole('button', { name: '미리보기' }));

    await waitFor(() => expect(mocked.runExpiry).toHaveBeenCalledWith(true));
    expect(await screen.findByRole('status')).toHaveTextContent('카드 2장');
    expect(screen.getByRole('status')).toHaveTextContent('소멸 예정 30,000원');
  });

  it('소멸 실행은 dryRun=false 로 부른다 — 미리보기와 같은 호출이면 아무것도 안 사라진다', async () => {
    mocked.runExpiry
      .mockResolvedValueOnce({ cardCount: 2, forfeitedTotal: 30000, dryRun: true })
      .mockResolvedValueOnce({ cardCount: 2, forfeitedTotal: 30000, dryRun: false });
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);
    await user.click(screen.getByRole('button', { name: '미리보기' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '소멸 실행' })).toBeEnabled());

    await user.click(screen.getByRole('button', { name: '소멸 실행' }));

    await waitFor(() => expect(mocked.runExpiry).toHaveBeenLastCalledWith(false));
    expect(await screen.findByRole('status')).toHaveTextContent('소멸 완료: 카드 2장');
  });

  it('실행이 끝나면 미리보기가 사라지고 소멸 버튼이 다시 잠긴다 — 두 번 누르면 두 번 돈다', async () => {
    mocked.runExpiry
      .mockResolvedValueOnce({ cardCount: 2, forfeitedTotal: 30000, dryRun: true })
      .mockResolvedValueOnce({ cardCount: 2, forfeitedTotal: 30000, dryRun: false });
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);
    await user.click(screen.getByRole('button', { name: '미리보기' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '소멸 실행' })).toBeEnabled());

    await user.click(screen.getByRole('button', { name: '소멸 실행' }));

    await waitFor(() =>
      expect(screen.getByRole('button', { name: '소멸 실행' })).toBeDisabled());
    expect(screen.queryByText(/^미리보기: /)).not.toBeInTheDocument();
  });

  it('미리보기 실패는 미리보기 문구로 드러난다', async () => {
    mocked.runExpiry.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);

    await user.click(screen.getByRole('button', { name: '미리보기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('소멸 미리보기에 실패했습니다.');
    expect(screen.getByRole('button', { name: '소멸 실행' })).toBeDisabled();
  });

  it('소멸 실행 실패는 실행 문구로 드러난다 — 미리보기 실패와 섞이면 어디서 멈췄는지 모른다', async () => {
    mocked.runExpiry
      .mockResolvedValueOnce({ cardCount: 2, forfeitedTotal: 30000, dryRun: true })
      .mockRejectedValueOnce(new Error('boom'));
    const user = userEvent.setup();
    render(<GiftCardConsolePage />);
    await user.click(screen.getByRole('button', { name: '미리보기' }));
    await waitFor(() => expect(screen.getByRole('button', { name: '소멸 실행' })).toBeEnabled());

    await user.click(screen.getByRole('button', { name: '소멸 실행' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('소멸 실행에 실패했습니다.');
  });
});
