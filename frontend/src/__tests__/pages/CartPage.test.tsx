import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import CartPage from '@/pages/CartPage';
import { orderApi } from '@/api/order';
import { paymentApi } from '@/api/payment';
import { couponApi } from '@/api/coupon';
import { privacyConsentApi, type PrivacyConsentTerms } from '@/api/privacyConsent';
import type { CartItem } from '@/contexts/useCart';

const removeItem = vi.fn();
const updateQuantity = vi.fn();
const clearCart = vi.fn();

let cartItems: CartItem[] = [];

vi.mock('@/contexts/useCart', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/contexts/useCart')>();
  return {
    ...actual,
    useCart: () => ({
      items: cartItems,
      addItem: vi.fn(),
      removeItem,
      updateQuantity,
      clearCart,
      totalAmount: cartItems.reduce((s, i) => s + i.product.price * i.quantity, 0),
      totalCount: cartItems.reduce((s, i) => s + i.quantity, 0),
      loading: false,
      syncing: false,
      serverBacked: false,
    }),
  };
});

vi.mock('@/api/order', () => ({ orderApi: { createMultiItemOrder: vi.fn() } }));
vi.mock('@/api/payment', () => ({
  paymentApi: { createPayment: vi.fn(), authorizePayment: vi.fn(), capturePayment: vi.fn() },
}));
vi.mock('@/api/coupon', () => ({ couponApi: { preview: vi.fn(), use: vi.fn() } }));

// 서버 호출만 가짜다. ready 판정과 acceptances 변환은 진짜를 쓴다 — 그것까지 가짜면 "동의 없이
// 주문 버튼이 열리는가"를 검사하지 못한다.
vi.mock('@/api/privacyConsent', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/privacyConsent')>()),
  privacyConsentApi: { terms: vi.fn(), ofOrder: vi.fn() },
}));

const mockedOrder = vi.mocked(orderApi);
const mockedPayment = vi.mocked(paymentApi);
const mockedCoupon = vi.mocked(couponApi);
const mockedConsentTerms = vi.mocked(privacyConsentApi.terms);

const item = (over: Record<string, unknown> = {}, quantity = 1): CartItem =>
  ({
    product: {
      id: 1,
      name: '티셔츠',
      description: '면 100%',
      price: 20000,
      stockQuantity: 5,
      status: 'ACTIVE',
      availableForSale: true,
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      primaryImageUrl: undefined,
      ...over,
    },
    quantity,
  }) as CartItem;

const renderPage = () => render(<MemoryRouter><CartPage /></MemoryRouter>);

/**
 * 배송지는 주문서에 굳는 값이라 서버가 필수로 요구한다(없으면 400). 화면도 다 채우기 전에는
 * 주문 버튼을 잠그므로, 결제 흐름 테스트는 먼저 이걸 채워야 한다.
 */
const fillAddress = async () => {
  await userEvent.type(screen.getByLabelText('받는 분'), '홍길동');
  await userEvent.type(screen.getByLabelText('연락처'), '010-1234-5678');
  await userEvent.type(screen.getByLabelText('우편번호'), '06236');
  await userEvent.type(screen.getByLabelText('주소'), '서울시 강남구 테헤란로 1');
};

/** fillAddress 가 채운 그대로. 선택 항목은 빈 문자열로 남는다. */
const FILLED_ADDRESS = {
  recipientName: '홍길동',
  phone: '010-1234-5678',
  postalCode: '06236',
  address1: '서울시 강남구 테헤란로 1',
  address2: '',
  deliveryMemo: '',
};

/** 결제 화면이 받아 오는 동의 문안. 필수 하나 + 선택 하나 — 둘의 취급이 다르다. */
const REQUIRED_TERMS: PrivacyConsentTerms = {
  code: 'THIRD_PARTY_DELIVERY',
  version: 2,
  consentType: 'THIRD_PARTY_PROVISION',
  title: '배송을 위한 개인정보 제3자 제공 동의',
  recipient: '배송업체',
  purpose: '주문 상품의 배송',
  providedItems: '받는 분 이름, 휴대전화번호, 주소',
  retention: '배송 완료 후 90일',
  body: '전문입니다',
  required: true,
  effectiveFrom: '2026-07-28T00:00:00',
};

const OPTIONAL_TERMS: PrivacyConsentTerms = {
  ...REQUIRED_TERMS,
  code: 'MARKETING_MESSAGE',
  consentType: 'MARKETING',
  title: '광고성 정보 수신 동의',
  recipient: null,
  required: false,
};

/** 필수만 체크했을 때 서버로 나가는 목록. 선택은 "묻고 거절함"으로 함께 실린다. */
const AGREED_ACCEPTANCES = [
  { termsCode: 'THIRD_PARTY_DELIVERY', termsVersion: 2, agreed: true },
  { termsCode: 'MARKETING_MESSAGE', termsVersion: 2, agreed: false },
];

/**
 * 필수 동의만 체크한다. 선택 항목은 일부러 손대지 않는다 — 그래야 "선택을 안 눌러도 주문이
 * 되는가"와 "안 누른 선택이 거절로 실려 나가는가"가 함께 검사된다.
 */
const agreeRequiredConsent = async () => {
  await userEvent.click(await screen.findByLabelText(/배송을 위한 개인정보 제3자 제공 동의/));
};

/** 주문을 낼 수 있는 상태(배송지 + 필수 동의)까지 만든다. */
const fillCheckoutForm = async () => {
  await fillAddress();
  await agreeRequiredConsent();
};

/**
 * 서버가 돌려주는 다건 주문. 금액을 서버가 확정한다는 게 이 경로의 요점이라, 테스트에서도
 * 화면이 보낸 값이 아니라 <b>여기 적힌 값</b>이 화면에 그대로 나와야 한다.
 */
const serverOrder = (over: Record<string, unknown> = {}) => ({
  id: 100,
  userId: 1,
  amount: 20000,
  status: 'CREATED',
  subtotal: 20000,
  discountAmount: 0,
  shippingFee: 0,
  createdAt: '2026-01-01T00:00:00Z',
  items: [{
    id: 900, productId: 1, variantId: null, sku: null, productName: '티셔츠',
    unitPrice: 20000, quantity: 1, lineAmount: 20000, allocatedDiscount: 0, netAmount: 20000,
  }],
  ...over,
});

beforeEach(() => {
  vi.clearAllMocks();
  cartItems = [item()];
  mockedOrder.createMultiItemOrder.mockResolvedValue(serverOrder() as never);
  mockedPayment.createPayment.mockResolvedValue({ id: 500, amount: 20000, status: 'READY' } as never);
  mockedPayment.authorizePayment.mockResolvedValue({ id: 500, status: 'AUTHORIZED' } as never);
  mockedPayment.capturePayment.mockResolvedValue({ id: 500, status: 'CAPTURED' } as never);
  mockedConsentTerms.mockResolvedValue([REQUIRED_TERMS, OPTIONAL_TERMS]);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('CartPage — 목록', () => {
  it('비어 있으면 상품 보러 가기를 안내한다', () => {
    cartItems = [];
    renderPage();

    expect(screen.getByText('장바구니가 비어있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '상품 보러 가기' })).toBeInTheDocument();
  });

  it('담긴 상품과 합계를 보여 준다', () => {
    cartItems = [item({}, 2)];
    renderPage();

    expect(screen.getByText('티셔츠')).toBeInTheDocument();
    expect(screen.getByText('총 2개 상품')).toBeInTheDocument();
    expect(screen.getByText('2개')).toBeInTheDocument();
  });

  it('수량 증감과 삭제가 컨텍스트로 전달된다', async () => {
    cartItems = [item({}, 2)];
    renderPage();
    const row = screen.getByText('티셔츠').closest('div')!.parentElement as HTMLElement;
    const buttons = within(row).getAllByRole('button');

    await userEvent.click(buttons[0]); // 감소
    expect(updateQuantity).toHaveBeenCalledWith(1, 1, null);

    await userEvent.click(buttons[1]); // 증가
    expect(updateQuantity).toHaveBeenCalledWith(1, 3, null);

    await userEvent.click(buttons[2]); // 삭제
    expect(removeItem).toHaveBeenCalledWith(1, null);
  });

  it('재고까지 담았으면 증가 버튼이 잠긴다', () => {
    cartItems = [item({ stockQuantity: 2 }, 2)];
    renderPage();
    const row = screen.getByText('티셔츠').closest('div')!.parentElement as HTMLElement;

    expect(within(row).getAllByRole('button')[1]).toBeDisabled();
  });

  it('전체 삭제를 누르면 카트를 비운다', async () => {
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: '전체 삭제' }));

    expect(clearCart).toHaveBeenCalled();
  });
});

describe('CartPage — 일반 결제', () => {
  it('장바구니 전체를 주문 1건으로 만들고 결제→승인→확정 후 카트를 비운다', async () => {
    cartItems = [item({ id: 1, name: '티셔츠' }), item({ id: 2, name: '바지', price: 30000 }, 2)];
    mockedOrder.createMultiItemOrder.mockResolvedValue(serverOrder({
      amount: 80000, subtotal: 80000,
      items: [
        { id: 900, productId: 1, productName: '티셔츠', unitPrice: 20000, quantity: 1, lineAmount: 20000, allocatedDiscount: 0, netAmount: 20000 },
        { id: 901, productId: 2, productName: '바지', unitPrice: 30000, quantity: 2, lineAmount: 60000, allocatedDiscount: 0, netAmount: 60000 },
      ],
    }) as never);
    renderPage();
    await fillCheckoutForm();

    await userEvent.click(screen.getByRole('button', { name: '2개 상품 전체 주문하기' }));

    expect(await screen.findByText('주문 완료!')).toBeInTheDocument();
    // 주문도 결제도 한 건. 상품 수만큼 쪼개지 않는다.
    expect(mockedOrder.createMultiItemOrder).toHaveBeenCalledTimes(1);
    expect(mockedPayment.capturePayment).toHaveBeenCalledTimes(1);
    expect(clearCart).toHaveBeenCalled();
  });

  it('금액이 아니라 라인(무엇을 몇 개)만 보낸다', async () => {
    cartItems = [item({ id: 1 }, 3)];
    renderPage();
    await fillCheckoutForm();

    await userEvent.click(screen.getByRole('button', { name: '1개 상품 전체 주문하기' }));

    await waitFor(() =>
      expect(mockedOrder.createMultiItemOrder).toHaveBeenCalledWith(
        1, [{ productId: 1, variantId: null, quantity: 3 }], FILLED_ADDRESS, AGREED_ACCEPTANCES, null, expect.any(String),
      ),
    );
  });

  it('주문이 실패하면 사유를 남기고 카트를 비우지 않는다', async () => {
    mockedOrder.createMultiItemOrder.mockRejectedValue({ response: { data: { message: '재고 부족' } } });
    renderPage();
    await fillCheckoutForm();

    await userEvent.click(screen.getByRole('button', { name: '1개 상품 전체 주문하기' }));

    expect(await screen.findByText('주문 실패')).toBeInTheDocument();
    expect(screen.getByText('재고 부족')).toBeInTheDocument();
    expect(clearCart).not.toHaveBeenCalled();
  });

  it('쿠폰 코드를 함께 보내고, 사용 기록은 서버에 맡긴다', async () => {
    mockedCoupon.preview.mockResolvedValue({
      valid: true,
      message: '',
      subtotal: 20000,
      discountAmount: 2000,
      eligibleAmount: 20000,
      finalAmount: 18000,
    } as never);
    mockedOrder.createMultiItemOrder.mockResolvedValue(serverOrder({
      amount: 18000, discountAmount: 2000,
    }) as never);
    renderPage();
    await fillCheckoutForm();
    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));
    await screen.findByText('쿠폰 적용됨:');

    await userEvent.click(screen.getByRole('button', { name: '1개 상품 전체 주문하기' }));

    await waitFor(() =>
      expect(mockedOrder.createMultiItemOrder).toHaveBeenCalledWith(
        1, [{ productId: 1, variantId: null, quantity: 1 }], FILLED_ADDRESS, AGREED_ACCEPTANCES, 'WELCOME10', expect.any(String),
      ),
    );
    // 서버가 같은 트랜잭션에서 기록한다. 여기서 또 부르면 쿠폰이 두 번 소진된다.
    expect(mockedCoupon.use).not.toHaveBeenCalled();
  });

  it('완료 화면의 금액은 서버가 확정한 값을 그대로 보여 준다', async () => {
    mockedOrder.createMultiItemOrder.mockResolvedValue(serverOrder({
      amount: 21000, subtotal: 20000, discountAmount: 2000, shippingFee: 3000,
    }) as never);
    renderPage();
    await fillCheckoutForm();

    await userEvent.click(screen.getByRole('button', { name: '1개 상품 전체 주문하기' }));

    await screen.findByText('주문 완료!');
    expect(screen.getByText('₩21,000')).toBeInTheDocument();
    expect(screen.getByText('-₩2,000')).toBeInTheDocument();
    expect(screen.getByText('₩3,000')).toBeInTheDocument();
  });
});

describe('CartPage — 토스페이먼츠', () => {
  const chooseToss = async () => {
    renderPage();
    await fillCheckoutForm();
    await userEvent.selectOptions(screen.getByRole('combobox'), 'TOSS_PAYMENTS');
  };

  it('결제 수단을 토스로 바꾸면 버튼 문구가 금액을 포함해 바뀐다', async () => {
    await chooseToss();

    expect(screen.getByRole('button', { name: /토스페이먼츠로 ₩20,000 결제/ })).toBeInTheDocument();
  });

  it('주문을 먼저 만들고, 서버가 준 금액으로 결제창을 연다', async () => {
    // 화면 예상가(20000)와 서버 확정가(18500)가 다른 상황. 결제창에 가야 할 것은 후자다.
    mockedOrder.createMultiItemOrder.mockResolvedValue(serverOrder({
      amount: 18500, discountAmount: 2000, shippingFee: 500,
    }) as never);
    const requestPayment = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('TossPayments', vi.fn(() => ({ requestPayment })));
    await chooseToss();

    await userEvent.click(screen.getByRole('button', { name: /토스페이먼츠로/ }));

    await waitFor(() => expect(requestPayment).toHaveBeenCalled());
    const [, options] = requestPayment.mock.calls[0];
    expect(options.amount).toBe(18500);
    expect(options.successUrl).toContain('type=cart');
    expect(options.successUrl).toContain('dbOrderIds=100');
  });

  it('여러 상품이면 주문명을 "외 N개"로 요약한다', async () => {
    cartItems = [item({ id: 1, name: '티셔츠' }), item({ id: 2, name: '바지' })];
    const requestPayment = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('TossPayments', vi.fn(() => ({ requestPayment })));
    await chooseToss();

    await userEvent.click(screen.getByRole('button', { name: /토스페이먼츠로/ }));

    await waitFor(() => expect(requestPayment).toHaveBeenCalled());
    expect(requestPayment.mock.calls[0][1].orderName).toBe('티셔츠 외 1개');
  });

  it('주문 생성이 실패하면 결제창을 열지 않고 장바구니로 돌아온다', async () => {
    mockedOrder.createMultiItemOrder.mockRejectedValue({ response: { data: { message: '품절' } } });
    const requestPayment = vi.fn();
    vi.stubGlobal('TossPayments', vi.fn(() => ({ requestPayment })));
    await chooseToss();

    await userEvent.click(screen.getByRole('button', { name: /토스페이먼츠로/ }));

    expect(await screen.findByText('주문 생성 실패: 품절')).toBeInTheDocument();
    expect(requestPayment).not.toHaveBeenCalled();
  });

  it('결제창을 열지 못하면 사유를 남기고 장바구니로 돌아온다', async () => {
    vi.stubGlobal('TossPayments', vi.fn(() => ({
      requestPayment: vi.fn().mockRejectedValue(new Error('사용자가 취소했습니다')),
    })));
    await chooseToss();

    await userEvent.click(screen.getByRole('button', { name: /토스페이먼츠로/ }));

    expect(await screen.findByText('사용자가 취소했습니다')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /토스페이먼츠로/ })).toBeInTheDocument();
  });
});

describe('CartPage — 배송지', () => {
  it('배송지가 비면 주문 버튼이 잠기고 안내가 뜬다 (도입 전엔 낼 자리조차 없었다)', () => {
    renderPage();

    expect(screen.getByRole('button', { name: '1개 상품 전체 주문하기' })).toBeDisabled();
    expect(
      screen.getByText('받는 분·연락처·우편번호·주소를 입력해야 주문할 수 있습니다.'),
    ).toBeInTheDocument();
  });

  it('필수 4항목과 필수 동의를 채우면 주문 버튼이 열린다', async () => {
    renderPage();

    await fillCheckoutForm();

    expect(screen.getByRole('button', { name: '1개 상품 전체 주문하기' })).toBeEnabled();
  });

  it('배송지만 채우면 아직 잠긴 채 남은 조건을 알린다', async () => {
    renderPage();

    await fillAddress();

    expect(screen.getByRole('button', { name: '1개 상품 전체 주문하기' })).toBeDisabled();
    expect(
      screen.getByText('필수 개인정보 동의 항목에 동의해야 주문할 수 있습니다.'),
    ).toBeInTheDocument();
  });

  it('필수 항목이 하나라도 비면 여전히 잠긴다 (우편번호 누락)', async () => {
    renderPage();

    await userEvent.type(screen.getByLabelText('받는 분'), '홍길동');
    await userEvent.type(screen.getByLabelText('연락처'), '010-1234-5678');
    await userEvent.type(screen.getByLabelText('주소'), '서울시 강남구 테헤란로 1');

    expect(screen.getByRole('button', { name: '1개 상품 전체 주문하기' })).toBeDisabled();
    expect(mockedOrder.createMultiItemOrder).not.toHaveBeenCalled();
  });
});

/**
 * 장바구니 결제도 받는 사람의 이름·연락처·주소를 택배사로 넘긴다. 주문 화면과 같은 자물쇠가
 * 걸려 있어야 하는 이유다 — 한쪽만 걸면 다른 쪽이 그대로 우회로가 된다.
 */
describe('CartPage — 개인정보 동의', () => {
  it('문안을 못 받아 오면 버튼이 닫힌 채 이유를 보여 준다 — 빈 목록이 "필수 0건"이 되면 안 된다', async () => {
    mockedConsentTerms.mockRejectedValue(new Error('down'));
    renderPage();

    await fillAddress();

    expect(await screen.findByText(/동의 문안을 불러오지 못했습니다/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '1개 상품 전체 주문하기' })).toBeDisabled();
  });

  it('선택 항목은 안 눌러도 주문되고, 거절로 함께 실려 나간다', async () => {
    renderPage();
    await fillCheckoutForm();

    await userEvent.click(screen.getByRole('button', { name: '1개 상품 전체 주문하기' }));

    await waitFor(() => expect(mockedOrder.createMultiItemOrder).toHaveBeenCalled());
    const [, , , consents] = mockedOrder.createMultiItemOrder.mock.calls[0];
    expect(consents).toEqual(AGREED_ACCEPTANCES);
  });

  it('409 로 거절되면 문안을 다시 받고 체크를 지운다 — 읽지 않은 문장에 동의가 남지 않도록', async () => {
    mockedOrder.createMultiItemOrder.mockRejectedValue({
      response: { status: 409, data: { code: 'PRIVACY_CONSENT_TERMS_STALE' } },
    });
    renderPage();
    await fillCheckoutForm();

    await userEvent.click(screen.getByRole('button', { name: '1개 상품 전체 주문하기' }));

    expect(await screen.findByText(/동의 문안이 변경되었습니다/)).toBeInTheDocument();
    expect(mockedConsentTerms).toHaveBeenCalledTimes(2);
    expect(screen.getByLabelText(/배송을 위한 개인정보 제3자 제공 동의/)).not.toBeChecked();
  });
});
