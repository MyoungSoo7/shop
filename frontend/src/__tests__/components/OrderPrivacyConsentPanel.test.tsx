import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import OrderPrivacyConsentPanel from '@/components/order/OrderPrivacyConsentPanel';
import { privacyConsentApi, type OrderPrivacyConsent } from '@/api/privacyConsent';

// 서버로 나가는 호출만 가짜다. 유형 라벨은 진짜를 쓴다 — 그것까지 가짜면 화면이 코드 대신
// 사람이 읽는 말을 보여 주는지 검사하지 못한다.
vi.mock('@/api/privacyConsent', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/privacyConsent')>()),
  privacyConsentApi: { terms: vi.fn(), ofOrder: vi.fn() },
}));

const mockedOfOrder = vi.mocked(privacyConsentApi.ofOrder);

beforeEach(() => vi.clearAllMocks());

const consent = (over: Partial<OrderPrivacyConsent> = {}): OrderPrivacyConsent => ({
  termsCode: 'THIRD_PARTY_DELIVERY',
  termsVersion: 2,
  consentType: 'THIRD_PARTY_PROVISION',
  agreed: true,
  recipient: '배송업체',
  purpose: '주문 상품의 배송',
  providedItems: '받는 분 이름, 휴대전화번호, 주소',
  retention: '배송 완료 후 90일',
  agreedAt: '2026-08-27T10:00:00',
  bodyUnchanged: true,
  ...over,
});

const open = async () => {
  fireEvent.click(screen.getByText(/개인정보 동의 내역/));
  await waitFor(() => expect(mockedOfOrder).toHaveBeenCalledWith(42));
};

/**
 * 고객이 자기 주문의 동의 내역을 확인하는 자리.
 *
 * <p>여기서 지키는 것 셋 — <b>접혀 있는 동안 요청이 나가지 않는다</b>(주문 목록 한 장에 주문
 * 수만큼 요청이 나가지 않도록), <b>거절도 보여 준다</b>("물었고 거절했다"와 "묻지 않았다"는
 * 다른 사실이다), <b>이력이 없으면 없다고 적는다</b>(없는 거절을 지어내지 않는다).
 */
describe('OrderPrivacyConsentPanel — 내 주문에서 무엇에 동의했는지', () => {
  it('접혀 있는 동안에는 서버를 부르지 않는다', () => {
    render(<OrderPrivacyConsentPanel orderId={42} />);

    expect(mockedOfOrder).not.toHaveBeenCalled();
  });

  it('펼치면 그 주문의 이력을 한 번만 읽는다 — 접었다 펴도 다시 부르지 않는다', async () => {
    mockedOfOrder.mockResolvedValue([consent()]);
    render(<OrderPrivacyConsentPanel orderId={42} />);

    await open();
    fireEvent.click(screen.getByText(/개인정보 동의 내역/));
    fireEvent.click(screen.getByText(/개인정보 동의 내역/));

    expect(mockedOfOrder).toHaveBeenCalledTimes(1);
  });

  it('동의 당시 고지 내용을 그대로 보여 준다 — 지금 문안이 아니라 그때 문안이다', async () => {
    mockedOfOrder.mockResolvedValue([consent()]);
    render(<OrderPrivacyConsentPanel orderId={42} />);

    await open();

    expect(await screen.findByText('동의함')).toBeInTheDocument();
    expect(screen.getByText('제3자 제공')).toBeInTheDocument();
    expect(screen.getByText('THIRD_PARTY_DELIVERY v2')).toBeInTheDocument();
    expect(screen.getByText(/제공받는 자: 배송업체/)).toBeInTheDocument();
    expect(screen.getByText(/목적: 주문 상품의 배송/)).toBeInTheDocument();
    expect(screen.getByText(/항목: 받는 분 이름, 휴대전화번호, 주소/)).toBeInTheDocument();
    expect(screen.getByText(/보유·이용: 배송 완료 후 90일/)).toBeInTheDocument();
  });

  it('거절한 항목은 "동의 안 함"으로 남는다 — 목록에서 빼면 묻지 않은 것과 같아진다', async () => {
    mockedOfOrder.mockResolvedValue([consent({ termsCode: 'MARKETING_MESSAGE', consentType: 'MARKETING', agreed: false, recipient: null })]);
    render(<OrderPrivacyConsentPanel orderId={42} />);

    await open();

    expect(await screen.findByText('동의 안 함')).toBeInTheDocument();
    expect(screen.queryByText(/제공받는 자:/)).not.toBeInTheDocument();
  });

  it('동의 이후 문안이 바뀌었으면 그 사실을 감추지 않는다', async () => {
    mockedOfOrder.mockResolvedValue([consent({ bodyUnchanged: false })]);
    render(<OrderPrivacyConsentPanel orderId={42} />);

    await open();

    expect(await screen.findByText(/동의 이후 내용이 변경되었습니다/)).toBeInTheDocument();
  });

  it('이력이 없으면 "그때는 받지 않았다"로 적는다 — 없는 거절을 지어내지 않는다', async () => {
    mockedOfOrder.mockResolvedValue([]);
    render(<OrderPrivacyConsentPanel orderId={42} />);

    await open();

    expect(await screen.findByText('이 주문에는 기록된 동의 내역이 없습니다.')).toBeInTheDocument();
  });

  it('읽지 못하면 실패를 말한다 — 빈 화면이 "동의 안 받음"으로 읽히지 않도록', async () => {
    mockedOfOrder.mockRejectedValue(new Error('boom'));
    render(<OrderPrivacyConsentPanel orderId={42} />);

    await open();

    expect(await screen.findByText('동의 내역을 불러오지 못했습니다.')).toBeInTheDocument();
    expect(screen.queryByText('이 주문에는 기록된 동의 내역이 없습니다.')).not.toBeInTheDocument();
  });
});
