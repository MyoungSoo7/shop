import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import MyBalancesPage from '@/pages/MyBalancesPage';
import { pointApi } from '@/api/point';
import { giftCardApi } from '@/api/giftCard';

/**
 * 이 화면이 지켜야 하는 규율은 둘이다.
 *
 * <p>① <b>포인트와 기프트카드를 합치지 않는다.</b> 둘은 회계에서도 다른 계정이고 유효기간·사용
 * 규칙도 다르다. 한 숫자로 합치면 "왜 이만큼밖에 못 쓰지"에 화면이 답할 수 없다.
 *
 * <p>② <b>등록 실패 문구를 화면이 지어내지 않는다.</b> 서버가 사유를 구분하지 않는 것은 유효한
 * 코드의 존재를 흘리지 않기 위함인데, 화면이 "이미 등록된 코드입니다" 같은 추측을 보여 주면
 * 그 방어가 무너진다.
 */

vi.mock('@/api/point', () => ({ pointApi: { myBalance: vi.fn() } }));
vi.mock('@/api/giftCard', () => ({ giftCardApi: { myBalance: vi.fn(), redeem: vi.fn() } }));

const mockedPoint = vi.mocked(pointApi);
const mockedGiftCard = vi.mocked(giftCardApi);

beforeEach(() => {
  vi.clearAllMocks();
  mockedPoint.myBalance.mockResolvedValue({ userId: 7, available: 1200 } as never);
  mockedGiftCard.myBalance.mockResolvedValue({ available: 5000 } as never);
});

describe('MyBalancesPage — 잔액 표시', () => {
  it('포인트와 기프트카드를 각각 따로 보여 준다 — 합계 하나로 뭉개지 않는다', async () => {
    render(<MyBalancesPage />);

    expect(await screen.findByTestId('point-balance')).toHaveTextContent('1,200P');
    expect(screen.getByTestId('giftcard-balance')).toHaveTextContent('5,000원');
  });

  it('잔액 조회는 사용자 식별자를 보내지 않는다 — 서버가 JWT 에서만 파생한다', async () => {
    render(<MyBalancesPage />);

    await waitFor(() => expect(mockedPoint.myBalance).toHaveBeenCalled());
    expect(mockedPoint.myBalance).toHaveBeenCalledWith();
    expect(mockedGiftCard.myBalance).toHaveBeenCalledWith();
  });

  it('조회가 실패하면 잔액 대신 오류를 알린다 — 0원으로 그리지 않는다', async () => {
    mockedPoint.myBalance.mockRejectedValue(new Error('boom'));

    render(<MyBalancesPage />);

    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByTestId('point-balance')).toHaveTextContent('—');
  });
});

describe('MyBalancesPage — 기프트카드 등록', () => {
  it('등록에 성공하면 잔액을 다시 불러온다', async () => {
    mockedGiftCard.redeem.mockResolvedValue({ faceAmount: 3000, codeLast4: '1234' } as never);

    render(<MyBalancesPage />);
    await screen.findByTestId('giftcard-balance');

    await userEvent.type(screen.getByLabelText('기프트카드 코드'), 'GC-1111-2222-3333-4444');
    await userEvent.click(screen.getByRole('button', { name: '등록' }));

    await waitFor(() => expect(mockedGiftCard.redeem).toHaveBeenCalledWith('GC-1111-2222-3333-4444'));
    // 등록 직후 재조회 — 최초 1회 + 등록 후 1회
    await waitFor(() => expect(mockedGiftCard.myBalance).toHaveBeenCalledTimes(2));
  });

  it('등록 실패 문구는 서버 메시지를 그대로 쓴다 — 화면이 사유를 추측하지 않는다', async () => {
    mockedGiftCard.redeem.mockRejectedValue({
      response: { data: { message: '등록할 수 없는 코드입니다.' } },
    });

    render(<MyBalancesPage />);
    await screen.findByTestId('giftcard-balance');

    await userEvent.type(screen.getByLabelText('기프트카드 코드'), 'GC-0000');
    await userEvent.click(screen.getByRole('button', { name: '등록' }));

    expect(await screen.findByText('등록할 수 없는 코드입니다.')).toBeInTheDocument();
  });
});
