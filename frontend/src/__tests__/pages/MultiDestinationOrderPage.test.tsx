import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import MultiDestinationOrderPage from '@/pages/MultiDestinationOrderPage';
import { orderApi } from '@/api/order';
import { privacyConsentApi, type PrivacyConsentTerms } from '@/api/privacyConsent';
import type { CartItem } from '@/contexts/useCart';

/**
 * 여러 곳 배송 화면.
 *
 * <p>여기서 지키려는 것은 하나다 — <b>배송지 수를 금액에도 수량에도 곱하지 않는다</b>. 옮겨 온
 * 원본(ssg-front)은 장바구니 총액을 배송지 수만큼 더해 청구하면서 재고는 한 벌만 뺐다. 이 화면은
 * 금액을 계산하지 않고 <b>수량 배분</b>만 받으므로, 아래 테스트들은 "무엇이 어느 배송지로
 * 나갔는가"와 "장바구니에 있는 것보다 많이 배정할 수 있는가"를 본다.
 */

const clearCart = vi.fn();
let cartItems: CartItem[] = [];

vi.mock('@/contexts/useCart', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/contexts/useCart')>();
  return {
    ...actual,
    useCart: () => ({
      items: cartItems,
      addItem: vi.fn(),
      removeItem: vi.fn(),
      updateQuantity: vi.fn(),
      clearCart,
      totalAmount: cartItems.reduce((s, i) => s + i.product.price * i.quantity, 0),
      totalCount: cartItems.reduce((s, i) => s + i.quantity, 0),
      loading: false,
      syncing: false,
      serverBacked: false,
    }),
  };
});

const mockAuth = { user: null, userId: 7 as number | null, loading: false, refresh: vi.fn() };
vi.mock('@/contexts/useAuth', () => ({ useAuth: () => mockAuth }));

vi.mock('@/api/order', () => ({ orderApi: { createMultiDestinationOrder: vi.fn() } }));

// 서버 호출만 가짜다. ready 판정과 acceptances 변환은 진짜를 쓴다 — 그것까지 가짜면 "동의 없이
// 주문 버튼이 열리는가"를 검사하지 못한다.
vi.mock('@/api/privacyConsent', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/privacyConsent')>()),
  privacyConsentApi: { terms: vi.fn(), ofOrder: vi.fn() },
}));

const mockedOrder = vi.mocked(orderApi);
const mockedTerms = vi.mocked(privacyConsentApi.terms);

const product = (id: number, name: string, price: number) => ({
  id, name, description: null, price, stockQuantity: 99,
  status: 'ACTIVE', availableForSale: true,
  createdAt: '2026-01-01T00:00:00Z', updatedAt: '2026-01-01T00:00:00Z',
});

const item = (id: number, name: string, price: number, quantity: number): CartItem =>
  ({ product: product(id, name, price), quantity }) as unknown as CartItem;

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

const renderPage = () => render(<MemoryRouter><MultiDestinationOrderPage /></MemoryRouter>);

const section = (n: number) => screen.getByRole('region', { name: `배송지 ${n}` });

/** 배송지 n 의 주소 4칸을 채운다. 하나라도 비면 서버가 400 이라 화면이 먼저 막는다. */
const fillAddress = async (n: number, recipient: string, postal: string, address: string) => {
  const scope = within(section(n));
  await userEvent.type(scope.getByLabelText('받는 분'), recipient);
  await userEvent.type(scope.getByLabelText('연락처'), '010-1234-5678');
  await userEvent.type(scope.getByLabelText('우편번호'), postal);
  await userEvent.type(scope.getByLabelText('주소'), address);
};

/** 배송지 n 의 상품 수량 칸에 값을 적는다. */
const assign = async (n: number, productName: string, quantity: number) => {
  const input = within(section(n)).getByLabelText(productName);
  await userEvent.clear(input);
  await userEvent.type(input, String(quantity));
};

const agreeRequired = async () => {
  await userEvent.click(await screen.findByLabelText(/배송을 위한 개인정보 제3자 제공 동의/));
};

beforeEach(() => {
  vi.clearAllMocks();
  mockAuth.userId = 7;
  cartItems = [item(11, '머그컵', 12000, 3)];
  mockedTerms.mockResolvedValue([REQUIRED_TERMS]);
});

describe('MultiDestinationOrderPage — 진입 조건', () => {
  it('장바구니가 비어 있으면 담으러 가라고 안내한다', () => {
    cartItems = [];
    renderPage();

    expect(screen.getByRole('link', { name: /상품 담으러 가기/ })).toBeInTheDocument();
    expect(screen.queryByRole('region', { name: '배송지 1' })).not.toBeInTheDocument();
  });

  it('배송지 두 곳으로 시작한다 — 한 곳이면 이 화면이 아니라 평범한 결제다', () => {
    renderPage();

    expect(screen.getByRole('region', { name: '배송지 1' })).toBeInTheDocument();
    expect(screen.getByRole('region', { name: '배송지 2' })).toBeInTheDocument();
    // 둘뿐일 때는 뺄 수 없다 — 서버도 배송지 하나짜리 요청을 400 으로 거절한다.
    expect(screen.queryByRole('button', { name: '이 배송지 빼기' })).not.toBeInTheDocument();
  });

  it('배송지를 추가하면 세 번째가 생기고, 그때부터 뺄 수 있다', async () => {
    renderPage();

    await userEvent.click(screen.getByRole('button', { name: '배송지 추가' }));

    expect(screen.getByRole('region', { name: '배송지 3' })).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: '이 배송지 빼기' })).toHaveLength(3);
  });
});

describe('MultiDestinationOrderPage — 수량 배분', () => {
  it('장바구니에 있는 것보다 많이 배정할 수 없다', async () => {
    renderPage();

    await assign(1, '머그컵', 9);

    // 장바구니 수량이 3 이므로 3 에서 잘린다. 여기가 뚫리면 화면이 서버보다 먼저 거짓말을 한다.
    expect(within(section(1)).getByLabelText('머그컵')).toHaveValue(3);
  });

  it('한 배송지가 가져간 만큼 다른 배송지의 상한이 줄어든다', async () => {
    renderPage();

    await assign(1, '머그컵', 2);
    await assign(2, '머그컵', 3);

    // 남은 것은 1 개뿐이다 — 두 배송지 합이 장바구니 수량을 넘지 않는다.
    expect(within(section(2)).getByLabelText('머그컵')).toHaveValue(1);
  });

  it('배정이 남으면 몇 개가 남았는지 적고 주문 버튼을 잠근다', async () => {
    renderPage();

    await assign(1, '머그컵', 1);
    await assign(2, '머그컵', 1);
    await fillAddress(1, '김철수', '06134', '서울 강남구 테헤란로 1');
    await fillAddress(2, '이영희', '48058', '부산 해운대구 해운대해변로 2');
    await agreeRequired();

    expect(screen.getByText('아직 배정하지 않은 상품이 1개 있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /2곳으로 주문하기/ })).toBeDisabled();
  });

  it('상품을 하나도 못 받은 배송지가 있으면 잠근다 — 0 원 주문이 배송 큐에 뜨지 않게', async () => {
    renderPage();

    await assign(1, '머그컵', 3);
    await fillAddress(1, '김철수', '06134', '서울 강남구 테헤란로 1');
    await fillAddress(2, '이영희', '48058', '부산 해운대구 해운대해변로 2');
    await agreeRequired();

    expect(screen.getByText('상품을 하나도 배정하지 않은 배송지가 있습니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /2곳으로 주문하기/ })).toBeDisabled();
  });
});

describe('MultiDestinationOrderPage — 주문', () => {
  const RESULT = {
    destinationGroupId: 'group-9',
    totalAmount: 27000,
    orders: [
      {
        id: 101, userId: 7, amount: 15000, status: 'CREATED', subtotal: 12000,
        discountAmount: 0, shippingFee: 3000, createdAt: '2026-08-27T00:00:00Z',
        shippingAddress: {
          recipientName: '김철수', phone: '010-1234-5678', postalCode: '06134',
          address1: '서울 강남구 테헤란로 1', address2: '', deliveryMemo: '',
        },
        items: [],
      },
      {
        id: 102, userId: 7, amount: 12000, status: 'CREATED', subtotal: 24000,
        discountAmount: 0, shippingFee: 0, createdAt: '2026-08-27T00:00:00Z',
        shippingAddress: {
          recipientName: '이영희', phone: '010-1234-5678', postalCode: '48058',
          address1: '부산 해운대구 해운대해변로 2', address2: '', deliveryMemo: '',
        },
        items: [],
      },
    ],
  };

  /** 두 배송지에 1 개 / 2 개로 나누고 주소·동의까지 다 채운다. */
  const fillEverything = async () => {
    await assign(1, '머그컵', 1);
    await assign(2, '머그컵', 2);
    await fillAddress(1, '김철수', '06134', '서울 강남구 테헤란로 1');
    await fillAddress(2, '이영희', '48058', '부산 해운대구 해운대해변로 2');
    await agreeRequired();
  };

  it('배송지마다 그 배송지의 라인만 실어 보낸다 — 장바구니 전체를 두 번 보내지 않는다', async () => {
    mockedOrder.createMultiDestinationOrder.mockResolvedValue(RESULT);
    renderPage();

    await fillEverything();
    await userEvent.click(screen.getByRole('button', { name: /2곳으로 주문하기/ }));

    await waitFor(() => expect(mockedOrder.createMultiDestinationOrder).toHaveBeenCalled());
    const [userId, destinations, consents, key] =
      mockedOrder.createMultiDestinationOrder.mock.calls[0];

    expect(userId).toBe(7);
    expect(destinations).toHaveLength(2);
    expect(destinations[0].shippingAddress.recipientName).toBe('김철수');
    expect(destinations[0].lines).toEqual([{ productId: 11, quantity: 1 }]);
    expect(destinations[1].shippingAddress.recipientName).toBe('이영희');
    expect(destinations[1].lines).toEqual([{ productId: 11, quantity: 2 }]);
    // 나눠 보낸 수량의 합이 장바구니 수량과 같다 — 곱해지지 않았다는 것이 이 기능의 요점이다.
    expect(destinations.reduce((s, d) => s + d.lines[0].quantity, 0)).toBe(3);
    expect(consents).toEqual([
      { termsCode: 'THIRD_PARTY_DELIVERY', termsVersion: 2, agreed: true },
    ]);
    expect(key).toBeTruthy();
  });

  it('완료 화면은 서버가 확정한 합계를 그대로 보여 주고 장바구니를 비운다', async () => {
    mockedOrder.createMultiDestinationOrder.mockResolvedValue(RESULT);
    renderPage();

    await fillEverything();
    await userEvent.click(screen.getByRole('button', { name: /2곳으로 주문하기/ }));

    expect(await screen.findByText('주문이 접수되었습니다')).toBeInTheDocument();
    // 27,000 원은 두 주문(15,000 + 12,000)의 합이고 화면이 다시 더한 값이 아니다.
    expect(screen.getByText('₩27,000')).toBeInTheDocument();
    expect(screen.getByText(/묶음 번호 group-9/)).toBeInTheDocument();
    expect(screen.getByText(/주문 #101/)).toBeInTheDocument();
    expect(screen.getByText(/주문 #102/)).toBeInTheDocument();
    expect(clearCart).toHaveBeenCalled();
  });

  it('실패하면 사유를 적고 완료 화면으로 넘어가지 않는다', async () => {
    mockedOrder.createMultiDestinationOrder.mockRejectedValue(new Error('상품이 품절되었습니다'));
    renderPage();

    await fillEverything();
    await userEvent.click(screen.getByRole('button', { name: /2곳으로 주문하기/ }));

    expect(await screen.findByText('상품이 품절되었습니다')).toBeInTheDocument();
    expect(screen.queryByText('주문이 접수되었습니다')).not.toBeInTheDocument();
    expect(clearCart).not.toHaveBeenCalled();
  });

  it('동의를 안 하면 주문 버튼이 열리지 않는다', async () => {
    renderPage();

    await assign(1, '머그컵', 1);
    await assign(2, '머그컵', 2);
    await fillAddress(1, '김철수', '06134', '서울 강남구 테헤란로 1');
    await fillAddress(2, '이영희', '48058', '부산 해운대구 해운대해변로 2');

    expect(await screen.findByText('필수 동의 항목을 확인해주세요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /2곳으로 주문하기/ })).toBeDisabled();
  });

  it('주소가 덜 찼으면 잠근다', async () => {
    renderPage();

    await assign(1, '머그컵', 1);
    await assign(2, '머그컵', 2);
    await fillAddress(1, '김철수', '06134', '서울 강남구 테헤란로 1');
    await agreeRequired();

    expect(screen.getByText('배송지 정보를 모두 채워주세요.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /2곳으로 주문하기/ })).toBeDisabled();
  });

  it('로그인 주체가 없으면 시도조차 하지 않는다 — 남의 id 를 보내면 403 이다', async () => {
    mockAuth.userId = null;
    renderPage();

    expect(await screen.findByText('로그인이 필요합니다.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /2곳으로 주문하기/ })).toBeDisabled();
    expect(mockedOrder.createMultiDestinationOrder).not.toHaveBeenCalled();
  });
});
