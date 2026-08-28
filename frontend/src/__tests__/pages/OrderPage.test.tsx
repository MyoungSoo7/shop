import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import OrderPage from '@/pages/OrderPage';
import { productApi } from '@/api/product';
import { orderApi } from '@/api/order';
import { paymentApi } from '@/api/payment';
import { reviewApi } from '@/api/review';
import { couponApi } from '@/api/coupon';
import { facetApi } from '@/api/facet';
import { privacyConsentApi, type PrivacyConsentTerms } from '@/api/privacyConsent';

const addItem = vi.fn();

vi.mock('@/contexts/useCart', () => ({
  useCart: () => ({ addItem }),
}));

// 이 화면은 Provider 없이 그대로 렌더한다. 상품 줄에 찜 하트가 붙은 뒤로는 useAuth·useToast 가
// 트리 안에서 불리는데, 둘 다 Provider 밖이면 throw 하는 훅이라 화면 전체가 빈 <div/> 로 죽는다.
// 컨텍스트 하나를 안 채운 대가가 "상품이 안 보인다"로 나타나므로, 여기 세 줄이 빠지면 이 파일의
// 26개가 통째로 무너진다.
const mockAuth = { user: null, userId: 7 as number | null, loading: false, refresh: vi.fn() };
vi.mock('@/contexts/useAuth', () => ({ useAuth: () => mockAuth }));
vi.mock('@/contexts/useToast', () => ({ useToast: () => ({ showToast: vi.fn() }) }));
vi.mock('@/api/wishlist', () => ({
  wishlistApi: {
    contains: vi.fn().mockResolvedValue({ productId: 0, wished: false }),
    add: vi.fn(),
    remove: vi.fn(),
  },
}));

vi.mock('@/api/product', () => ({
  productApi: { getAvailableProducts: vi.fn() },
}));
vi.mock('@/api/order', () => ({
  orderApi: { createMultiItemOrder: vi.fn() },
}));
vi.mock('@/api/payment', () => ({
  paymentApi: { createPayment: vi.fn(), authorizePayment: vi.fn(), capturePayment: vi.fn() },
}));
vi.mock('@/api/review', () => ({
  reviewApi: { getProductReviews: vi.fn() },
}));
vi.mock('@/api/coupon', () => ({
  couponApi: { preview: vi.fn(), use: vi.fn() },
}));

// 파셋 헬퍼(toggle/count)는 순수 함수라 실제 구현을 그대로 쓰고 네트워크 호출만 가짜로 바꾼다.
vi.mock('@/api/facet', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/facet')>();
  return { ...actual, facetApi: { search: vi.fn() } };
});

// 동의도 같다 — 서버 호출만 가짜다. ready 판정(필수를 다 체크했는가)과 acceptances 변환은
// 진짜를 쓴다. 그것까지 가짜로 두면 "동의 없이도 주문 버튼이 열리는가"를 검사하지 못한다.
vi.mock('@/api/privacyConsent', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/privacyConsent')>()),
  privacyConsentApi: { terms: vi.fn(), ofOrder: vi.fn() },
}));

const mockedProduct = vi.mocked(productApi);
const mockedOrder = vi.mocked(orderApi);
const mockedPayment = vi.mocked(paymentApi);
const mockedReview = vi.mocked(reviewApi);
const mockedCoupon = vi.mocked(couponApi);
const mockedFacet = vi.mocked(facetApi);
const mockedConsentTerms = vi.mocked(privacyConsentApi.terms);

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

const product = (over: Record<string, unknown> = {}) =>
  ({
    id: 1,
    name: '티셔츠',
    description: '면 100%',
    price: 20000,
    stockQuantity: 10,
    status: 'ACTIVE',
    primaryImageUrl: null,
    ...over,
  }) as never;

/** 서버가 확정해 돌려주는 주문. 단건도 다건과 같은 경로(/orders/multi)를 쓴다. */
const order = (over: Record<string, unknown> = {}) =>
  ({
    id: 100, userId: 1, amount: 20000, status: 'CREATED',
    subtotal: 20000, discountAmount: 0, shippingFee: 0,
    createdAt: '2026-01-01T00:00:00Z',
    items: [{
      id: 900, productId: 1, variantId: null, sku: null, productName: '티셔츠',
      unitPrice: 20000, quantity: 1, lineAmount: 20000, allocatedDiscount: 0, netAmount: 20000,
    }],
    ...over,
  }) as never;

const payment = (over: Record<string, unknown> = {}) =>
  ({
    id: 500,
    orderId: 100,
    amount: 20000,
    paymentMethod: 'CARD',
    status: 'READY',
    pgTransactionId: null,
    ...over,
  }) as never;

beforeEach(() => {
  vi.clearAllMocks();
  // mockAuth 는 모듈 수준 객체라 vi.clearAllMocks 가 되돌려 주지 않는다. 한 케이스가
  // userId 를 null 로 바꾸면 그 뒤 케이스들이 통째로 "로그인이 필요합니다"가 된다.
  mockAuth.userId = 7;
  mockAuth.loading = false;
  mockedProduct.getAvailableProducts.mockResolvedValue([product()] as never);
  mockedFacet.search.mockResolvedValue({ products: [], facets: [] } as never);
  mockedReview.getProductReviews.mockResolvedValue([] as never);
  mockedConsentTerms.mockResolvedValue([REQUIRED_TERMS, OPTIONAL_TERMS]);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

/**
 * 배송지는 주문서에 굳는 값이라 서버가 필수로 요구한다(없으면 400). 화면도 다 채우기 전에는
 * 주문 버튼을 잠근다.
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

/**
 * 필수 동의만 체크한다. 선택 항목은 일부러 손대지 않는다 — 그래야 "선택을 안 눌러도 주문이
 * 되는가"와 "안 누른 선택이 거절로 실려 나가는가"가 함께 검사된다.
 */
const agreeRequiredConsent = async () => {
  await userEvent.click(await screen.findByLabelText(/배송을 위한 개인정보 제3자 제공 동의/));
};

/** 상품만 고른다 — 배송지는 비어 있어 주문 버튼이 잠긴 상태. */
const selectProductOnly = async () => {
  render(<OrderPage />);
  await userEvent.click(await screen.findByText('티셔츠'));
};

/** 주문을 낼 수 있는 상태(상품 + 배송지 + 필수 동의)까지 만든다. */
const selectProduct = async () => {
  await selectProductOnly();
  await fillAddress();
  await agreeRequiredConsent();
};

describe('OrderPage — 상품 목록', () => {
  it('진입하면 판매 가능 상품을 읽어 보여 준다', async () => {
    render(<OrderPage />);

    expect(await screen.findByText('티셔츠')).toBeInTheDocument();
    expect(screen.getByText('재고 10개')).toBeInTheDocument();
    expect(mockedProduct.getAvailableProducts).toHaveBeenCalledTimes(1);
  });

  it('목록 조회가 실패하면 사유를 남긴다', async () => {
    mockedProduct.getAvailableProducts.mockRejectedValue(new Error('down'));
    render(<OrderPage />);

    expect(await screen.findByText('상품 목록을 불러오지 못했습니다.')).toBeInTheDocument();
  });

  it('상품이 없으면 그 사실을 알린다', async () => {
    mockedProduct.getAvailableProducts.mockResolvedValue([] as never);
    render(<OrderPage />);

    expect(await screen.findByText('판매 가능한 상품이 없습니다.')).toBeInTheDocument();
  });

  it('검색어에 걸리는 상품이 없으면 검색 결과 없음으로 구분해 알린다', async () => {
    render(<OrderPage />);
    await screen.findByText('티셔츠');

    await userEvent.type(screen.getByPlaceholderText('상품명을 입력하세요'), '없는상품');

    expect(await screen.findByText('검색 결과가 없습니다.')).toBeInTheDocument();
  });

  it('장바구니 담기는 카트에 넣고 잠시 체크 표시로 바뀐다', async () => {
    render(<OrderPage />);
    await screen.findByText('티셔츠');

    await userEvent.click(screen.getByTitle('장바구니 담기'));

    expect(addItem).toHaveBeenCalledWith(expect.objectContaining({ id: 1 }));
  });

  it('상품 줄에서 찜 하트를 눌러도 그 줄이 선택되지는 않는다', async () => {
    const { wishlistApi } = await import('@/api/wishlist');
    vi.mocked(wishlistApi.add).mockResolvedValue({ wished: true, changed: true, count: 1 });
    render(<OrderPage />);
    await screen.findByText('티셔츠');

    // 줄 전체가 "이 상품 선택"이라, 하트 클릭이 위로 전파되면 찜 한 번에 주문 대상이 바뀐다.
    await userEvent.click(screen.getByRole('button', { name: '찜하기' }));

    expect(wishlistApi.add).toHaveBeenCalled();
    expect(screen.queryByText('선택된 상품')).not.toBeInTheDocument();
  });

  it('상품을 고르기 전에는 주문 버튼이 잠겨 있다', async () => {
    render(<OrderPage />);
    await screen.findByText('티셔츠');

    expect(screen.getByRole('button', { name: '상품을 먼저 선택해주세요' })).toBeDisabled();
  });

  /*
   * 주문의 주인은 토큰이 정한다.
   *
   * 이 화면은 `const userId = 1` 을 들고 있었다. 서버는 본문의 userId 를 JWT 의 uid 와
   * 대조하므로(ResourceOwnership.requireSelfOrAdmin) 1번 사용자가 아닌 모든 계정이
   * 주문 버튼에서 403 을 받았고, 화면에는 "접근 권한이 없습니다" 로만 보여 인가 설정
   * 문제로 읽혔다. 아래 두 개는 "값이 없으면 보내지 않는다"를 못박는다 — 서버 대조가
   * 걸린 호출에 화면이 지어낸 id 를 실어 보내는 일이 다시 생기지 않게.
   */
  it('userId 를 모르는 동안에는 주문 버튼이 잠긴다', async () => {
    mockAuth.userId = null;
    mockAuth.loading = true;
    render(<OrderPage />);
    await screen.findByText('티셔츠');

    expect(screen.getByRole('button', { name: '불러오는 중...' })).toBeDisabled();
  });

  it('로그인 주체를 확인하지 못하면 주문을 시도하지 않는다', async () => {
    mockAuth.userId = null;
    render(<OrderPage />);
    await userEvent.click(await screen.findByText('티셔츠'));
    await fillAddress();
    await agreeRequiredConsent();

    expect(screen.getByRole('button', { name: '로그인이 필요합니다' })).toBeDisabled();
    expect(mockedOrder.createMultiItemOrder).not.toHaveBeenCalled();
    // 쿠폰 미리보기도 같은 대조를 받는다 — 칸 자체가 나오지 않아야 한다.
    expect(screen.queryByPlaceholderText(/쿠폰 코드 입력/)).not.toBeInTheDocument();
  });
});

describe('OrderPage — 상품 선택 후', () => {
  it('선택 요약·쿠폰 입력·리뷰 섹션이 함께 열린다', async () => {
    await selectProduct();

    expect(screen.getByText('선택된 상품')).toBeInTheDocument();
    expect(screen.getByPlaceholderText(/쿠폰 코드 입력/)).toBeInTheDocument();
    expect(await screen.findByText('상품 리뷰 (0개)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '주문하기' })).toBeEnabled();
  });

  it('리뷰가 있으면 개수와 평균 별점을 함께 보여 주고 펼칠 수 있다', async () => {
    mockedReview.getProductReviews.mockResolvedValue([
      { id: 1, productId: 1, userId: 7, rating: 5, content: '좋아요', createdAt: '2026-08-01T00:00:00Z' },
      { id: 2, productId: 1, userId: 8, rating: 3, content: '보통', createdAt: '2026-08-02T00:00:00Z' },
    ] as never);
    await selectProduct();

    await userEvent.click(await screen.findByText('상품 리뷰 (2개)'));

    // 접힘 헤더의 평균과 펼친 ReviewList 요약이 같은 값을 각각 보여 준다
    expect(screen.getAllByText('4.0').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('좋아요')).toBeInTheDocument();
  });

  it('쿠폰을 적용하면 할인 금액과 최종가를 반영한다', async () => {
    mockedCoupon.preview.mockResolvedValue({
      valid: true,
      message: '',
      subtotal: 20000,
      discountAmount: 2000,
      eligibleAmount: 20000,
      finalAmount: 18000,
    } as never);
    await selectProduct();

    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));

    expect(await screen.findByText('-₩2,000 할인')).toBeInTheDocument();
    expect(screen.getByText('쿠폰 적용됨:')).toBeInTheDocument();
  });
});

describe('OrderPage — 주문·결제 흐름', () => {
  it('주문 생성 → 결제 생성 → 승인 → 확정까지 진행한다', async () => {
    mockedOrder.createMultiItemOrder.mockResolvedValue(order());
    mockedPayment.createPayment.mockResolvedValue(payment());
    mockedPayment.authorizePayment.mockResolvedValue(payment({ status: 'AUTHORIZED' }));
    mockedPayment.capturePayment.mockResolvedValue(payment({ status: 'CAPTURED' }));
    await selectProduct();

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));
    expect(await screen.findByText('주문이 생성되었습니다')).toBeInTheDocument();
    // 금액은 보내지 않는다 — 라인만 보내고 서버가 확정한다.
    // 첫 인자가 mockAuth.userId(7) 인 것이 요점이다. 여기엔 1 이 박혀 있었고 화면도 1 을
    // 하드코딩하고 있어서 둘이 짝을 이뤄 초록이었다 — 서버는 토큰의 uid 와 대조하므로
    // 1번 사용자가 아닌 모든 계정이 주문 버튼에서 403 을 받았다.
    expect(mockedOrder.createMultiItemOrder).toHaveBeenCalledWith(
      7, [{ productId: 1, quantity: 1 }], FILLED_ADDRESS, AGREED_ACCEPTANCES, null, expect.any(String),
    );

    await userEvent.click(screen.getByRole('button', { name: '결제 진행하기' }));
    expect(await screen.findByText('결제 정보')).toBeInTheDocument();
    expect(mockedPayment.createPayment).toHaveBeenCalledWith({ orderId: 100, paymentMethod: 'CARD' });

    await userEvent.click(screen.getByRole('button', { name: '결제하기' }));

    await waitFor(() => expect(mockedPayment.capturePayment).toHaveBeenCalledWith(500), {
      timeout: 3000,
    });
    expect(await screen.findByText('결제가 완료되었습니다!')).toBeInTheDocument();
  });

  it('완료 후 새 주문을 누르면 처음 상태로 돌아간다', async () => {
    mockedOrder.createMultiItemOrder.mockResolvedValue(order());
    mockedPayment.createPayment.mockResolvedValue(payment());
    mockedPayment.authorizePayment.mockResolvedValue(payment({ status: 'AUTHORIZED' }));
    mockedPayment.capturePayment.mockResolvedValue(payment({ status: 'CAPTURED' }));
    await selectProduct();
    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));
    await userEvent.click(await screen.findByRole('button', { name: '결제 진행하기' }));
    await userEvent.click(await screen.findByRole('button', { name: '결제하기' }));
    await screen.findByText('결제가 완료되었습니다!', undefined, { timeout: 3000 });

    await userEvent.click(screen.getByRole('button', { name: '새로운 주문하기' }));

    expect(await screen.findByText('상품 선택 및 결제')).toBeInTheDocument();
  });

  it('주문 생성 실패는 사유를 남긴다', async () => {
    mockedOrder.createMultiItemOrder.mockRejectedValue({ response: { data: { message: '재고 부족' } } });
    await selectProduct();

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    expect(await screen.findByText('재고 부족')).toBeInTheDocument();
  });

  it('결제 생성 실패도 사유를 남긴다', async () => {
    mockedOrder.createMultiItemOrder.mockResolvedValue(order());
    mockedPayment.createPayment.mockRejectedValue(new Error('down'));
    await selectProduct();
    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    await userEvent.click(await screen.findByRole('button', { name: '결제 진행하기' }));

    expect(await screen.findByText('결제 생성에 실패했습니다.')).toBeInTheDocument();
  });

  it('승인 실패도 사유를 남긴다', async () => {
    mockedOrder.createMultiItemOrder.mockResolvedValue(order());
    mockedPayment.createPayment.mockResolvedValue(payment());
    mockedPayment.authorizePayment.mockRejectedValue(new Error('down'));
    await selectProduct();
    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));
    await userEvent.click(await screen.findByRole('button', { name: '결제 진행하기' }));

    await userEvent.click(await screen.findByRole('button', { name: '결제하기' }));

    expect(await screen.findByText('결제 승인에 실패했습니다.')).toBeInTheDocument();
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
    mockedOrder.createMultiItemOrder.mockResolvedValue(order({ amount: 18000, discountAmount: 2000 }));
    await selectProduct();
    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));
    await screen.findByText('쿠폰 적용됨:');

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    await waitFor(() =>
      expect(mockedOrder.createMultiItemOrder).toHaveBeenCalledWith(
        7, [{ productId: 1, quantity: 1 }], FILLED_ADDRESS, AGREED_ACCEPTANCES, 'WELCOME10', expect.any(String),
      ),
    );
    // 서버가 같은 트랜잭션에서 기록한다. 여기서 또 부르면 쿠폰이 두 번 소진된다.
    expect(mockedCoupon.use).not.toHaveBeenCalled();
  });

  it('주문 금액은 서버가 확정한 값을 그대로 보여 준다', async () => {
    mockedCoupon.preview.mockResolvedValue({
      valid: true,
      message: '',
      subtotal: 20000,
      discountAmount: 2000,
      eligibleAmount: 20000,
      finalAmount: 18000,
    } as never);
    // 화면 예상가(18000)와 서버 확정가(21000: 배송비 3000 포함)가 다른 상황.
    mockedOrder.createMultiItemOrder.mockResolvedValue(
      order({ amount: 21000, discountAmount: 2000, shippingFee: 3000 }));
    await selectProduct();
    await userEvent.type(screen.getByPlaceholderText(/쿠폰 코드 입력/), 'welcome10');
    await userEvent.click(screen.getByRole('button', { name: '적용' }));
    await screen.findByText('쿠폰 적용됨:');

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    expect(await screen.findByText('주문이 생성되었습니다')).toBeInTheDocument();
    expect(screen.getByText('₩21,000')).toBeInTheDocument();
    expect(screen.getByText('배송비 ₩3,000 포함')).toBeInTheDocument();
  });
});

describe('OrderPage — 배송지', () => {
  it('상품만 고르고 배송지가 비면 주문 버튼이 잠긴다', async () => {
    await selectProductOnly();

    expect(screen.getByRole('button', { name: '배송지를 입력해주세요' })).toBeDisabled();
    expect(mockedOrder.createMultiItemOrder).not.toHaveBeenCalled();
  });

  it('배송지를 채워도 필수 동의 전에는 잠긴 채 이유가 바뀐다', async () => {
    await selectProductOnly();

    await fillAddress();

    // 버튼 라벨이 곧 남은 조건이다 — "잠겼다"만 알려 주면 무엇을 더 해야 하는지 알 수 없다.
    expect(screen.getByRole('button', { name: '필수 동의 항목에 동의해주세요' })).toBeDisabled();
  });

  it('배송지와 필수 동의가 모두 차면 주문 버튼이 열린다', async () => {
    await selectProductOnly();

    await fillAddress();
    await agreeRequiredConsent();

    expect(screen.getByRole('button', { name: '주문하기' })).toBeEnabled();
  });
});

/**
 * 이 주문은 받는 사람의 이름·연락처·주소를 택배사로 넘긴다. 동의 없이 넘기면 개인정보 보호법
 * 제17조 위반이라, 화면은 <b>동의를 못 받은 상태에서 주문이 나가지 않는 것</b>을 보장해야 한다.
 *
 * <p>특히 문안을 <b>못 받아 왔을 때</b>가 위험하다. 빈 목록을 "필수 0건"으로 읽으면 조건이 저절로
 * 충족되어 동의 없이 버튼이 열린다 — 실패는 반드시 닫힌 쪽이어야 한다.
 */
describe('OrderPage — 개인정보 동의', () => {
  it('필수에 동의하지 않으면 주문이 나가지 않는다', async () => {
    await selectProductOnly();
    await fillAddress();

    expect(screen.getByRole('button', { name: '필수 동의 항목에 동의해주세요' })).toBeDisabled();
    expect(mockedOrder.createMultiItemOrder).not.toHaveBeenCalled();
  });

  it('선택 항목은 안 눌러도 주문되고, 거절로 함께 실려 나간다', async () => {
    mockedOrder.createMultiItemOrder.mockResolvedValue(order());
    await selectProduct();

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    await waitFor(() => expect(mockedOrder.createMultiItemOrder).toHaveBeenCalled());
    const [, , , consents] = mockedOrder.createMultiItemOrder.mock.calls[0];
    expect(consents).toEqual(AGREED_ACCEPTANCES);
  });

  it('문안을 못 받아 오면 버튼이 닫힌 채 이유를 보여 준다 — 빈 목록이 "필수 0건"이 되면 안 된다', async () => {
    mockedConsentTerms.mockRejectedValue(new Error('down'));
    await selectProductOnly();
    await fillAddress();

    expect(await screen.findByText(/동의 문안을 불러오지 못했습니다/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '필수 동의 항목에 동의해주세요' })).toBeDisabled();
  });

  it('409 로 거절되면 문안을 다시 받고 체크를 지운다 — 읽지 않은 문장에 동의가 남지 않도록', async () => {
    mockedOrder.createMultiItemOrder.mockRejectedValue({
      response: { status: 409, data: { code: 'PRIVACY_CONSENT_TERMS_STALE' } },
    });
    await selectProduct();

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    expect(await screen.findByText(/동의 문안이 변경되었습니다/)).toBeInTheDocument();
    expect(mockedConsentTerms).toHaveBeenCalledTimes(2);
    expect(screen.getByLabelText(/배송을 위한 개인정보 제3자 제공 동의/)).not.toBeChecked();
  });
});

describe('OrderPage — 토스페이먼츠', () => {
  const selectTossMethod = async () => {
    await selectProduct();
    await userEvent.selectOptions(screen.getByRole('combobox'), 'TOSS_PAYMENTS');
  };

  it('토스를 고르면 결제창 안내를 보여 준다', async () => {
    await selectTossMethod();

    expect(screen.getByText('주문하기를 누르면 토스페이먼츠 결제창이 열립니다.')).toBeInTheDocument();
  });

  it('주문하면 토스 결제창을 연다', async () => {
    const requestPayment = vi.fn().mockResolvedValue(undefined);
    vi.stubGlobal('TossPayments', vi.fn(() => ({ requestPayment })));
    mockedOrder.createMultiItemOrder.mockResolvedValue(order());
    await selectTossMethod();

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    await waitFor(() => expect(requestPayment).toHaveBeenCalledWith('카드', expect.objectContaining({
      amount: 20000,
      orderName: '티셔츠',
    })));
  });

  it('결제창을 열지 못하면 원인 문구를 보여 준다', async () => {
    vi.stubGlobal('TossPayments', vi.fn(() => ({
      requestPayment: vi.fn().mockRejectedValue(new Error('사용자가 취소했습니다')),
    })));
    mockedOrder.createMultiItemOrder.mockResolvedValue(order());
    await selectTossMethod();

    await userEvent.click(screen.getByRole('button', { name: '주문하기' }));

    expect(await screen.findByText('사용자가 취소했습니다')).toBeInTheDocument();
  });
});

describe('OrderPage — 옵션 파셋', () => {
  it('파셋 값을 고르면 그 선택으로 다시 질의하고 목록을 파셋 결과로 바꾼다', async () => {
    mockedFacet.search
      .mockResolvedValueOnce({
        products: [],
        facets: [
          {
            axisCode: 'COLOR',
            axisName: '색상',
            values: [{ code: 'RED', name: '빨강', productCount: 1, selected: false }],
          },
        ],
      } as never)
      .mockResolvedValueOnce({
        products: [product({ id: 2, name: '빨강 티셔츠' })],
        facets: [
          {
            axisCode: 'COLOR',
            axisName: '색상',
            values: [{ code: 'RED', name: '빨강', productCount: 1, selected: true }],
          },
        ],
      } as never);
    render(<OrderPage />);

    await userEvent.click(await screen.findByText(/빨강/));

    expect(await screen.findByText('빨강 티셔츠')).toBeInTheDocument();
    expect(mockedFacet.search).toHaveBeenLastCalledWith({ COLOR: ['RED'] });
  });

  it('파셋 조회가 실패해도 화면은 기존 목록으로 계속 동작한다', async () => {
    mockedFacet.search.mockRejectedValue(new Error('down'));
    render(<OrderPage />);

    expect(await screen.findByText('티셔츠')).toBeInTheDocument();
  });
});
