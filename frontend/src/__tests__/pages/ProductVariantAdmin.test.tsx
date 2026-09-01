import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>응답 껍질을 벗긴 값이 실제로 그려진다.</b> 서버는 {@code {"variant": {...}}} 로 감싸
 * 내보낸다. 안 벗기면 에러가 아니라 <b>빈 표</b>가 되므로, 표가 그려졌다는 것만으로는 통과라고
 * 할 수 없다 — SKU·재고 같은 실제 값을 짚어야 한다.
 *
 * <p>② <b>재고 차감에는 확인 단계가 있다.</b> 주문 없이 재고를 줄이는 조작이고 되돌리는 API 가
 * 없다. 확인 문구에 무엇을 몇 개에서 줄이는지 다시 적는다. 버튼만 두 번 누르게 하면 두 번째
 * 클릭은 첫 번째의 연장이라 아무것도 막지 못한다.
 *
 * <p>③ <b>빈 추가금은 0 이 아니라 미설정이다.</b> 0 을 보내면 "추가금 없음"을 명시한 것이 되어,
 * 나중에 기본값 정책이 바뀌어도 그 SKU 만 옛 값에 고정된다.
 *
 * <p>④ <b>판매가 계산은 한 곳에서 한다.</b> 할인가는 기본가에서 뺀 값이 아니라 확정된 판매가라,
 * 추가금을 겹쳐 더하면 화면 금액이 결제 금액과 갈린다.
 */

vi.mock('@/api/product', () => ({ productApi: { getAllProducts: vi.fn() } }));
vi.mock('@/api/productVariant', async () => {
  const actual = await vi.importActual<typeof import('@/api/productVariant')>('@/api/productVariant');
  return {
    ...actual,
    productVariantApi: {
      list: vi.fn(),
      create: vi.fn(),
      decreaseStock: vi.fn(),
      resolve: vi.fn(),
    },
    variantCostApi: {
      list: vi.fn(),
      setPurchasePrice: vi.fn(),
    },
  };
});

const { productApi } = await import('@/api/product');
const { productVariantApi, variantCostApi } = await import('@/api/productVariant');
const { ToastProvider } = await import('@/contexts/ToastContext');
const { default: ProductVariantAdmin } = await import('@/pages/ProductVariantAdmin');

const products = vi.mocked(productApi.getAllProducts);
const variants = vi.mocked(productVariantApi);
const costs = vi.mocked(variantCostApi);

const cost = (over: Record<string, unknown> = {}) => ({
  variantId: 7,
  sku: 'SKU-RED-L',
  optionName: '빨강/L',
  stockQuantity: 3,
  sellingPrice: 12000,
  purchasePrice: 7000,
  marginAmount: 5000,
  marginRate: 41.67,
  ...over,
});

const PRODUCT = {
  id: 1,
  name: '티셔츠',
  price: 10000,
  stockQuantity: 100,
  status: 'ACTIVE',
  availableForSale: true,
  createdAt: '2026-08-01T00:00:00+09:00',
  updatedAt: '2026-08-01T00:00:00+09:00',
};

const variant = (over: Record<string, unknown> = {}) => ({
  id: 7,
  productId: 1,
  sku: 'SKU-RED-L',
  optionName: '빨강/L',
  additionalPrice: 2000,
  discountPrice: null,
  discountRate: null,
  stockQuantity: 3,
  version: 0,
  status: 'ACTIVE',
  optionSignature: '색상:빨강|사이즈:L',
  ...over,
});

const renderPage = () => render(<ToastProvider><ProductVariantAdmin /></ToastProvider>);

/** 상품을 골라야 나머지 화면이 열린다 — 거의 모든 검사의 전제다. */
const pickProduct = async (user: ReturnType<typeof userEvent.setup>) => {
  await user.selectOptions(await screen.findByTestId('variant-product'), '1');
  await waitFor(() => expect(variants.list).toHaveBeenCalledWith(1));
};

describe('ProductVariantAdmin', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    products.mockResolvedValue([PRODUCT] as never);
    variants.list.mockResolvedValue([variant()] as never);
    costs.list.mockResolvedValue([cost()] as never);
  });

  it('상품을 고르기 전에는 옵션을 부르지 않는다', async () => {
    renderPage();

    expect(await screen.findByTestId('variant-no-product')).toBeInTheDocument();
    expect(variants.list).not.toHaveBeenCalled();
  });

  it('껍질을 벗긴 실제 값이 표에 그려진다 — 빈 표는 에러 없이 통과하므로', async () => {
    const user = userEvent.setup();
    renderPage();

    await pickProduct(user);

    const row = await screen.findByTestId('variant-row-SKU-RED-L');
    expect(within(row).getByText('빨강/L')).toBeInTheDocument();
    expect(within(row).getByText('3')).toBeInTheDocument();
  });

  it('판매가는 기본가에 추가금을 더한 값이다', async () => {
    const user = userEvent.setup();
    renderPage();

    await pickProduct(user);

    const row = await screen.findByTestId('variant-row-SKU-RED-L');
    expect(within(row).getByText('12,000원')).toBeInTheDocument();
  });

  it('할인가가 있으면 추가금을 겹쳐 더하지 않는다', async () => {
    variants.list.mockResolvedValue([variant({ discountPrice: 9000 })] as never);
    const user = userEvent.setup();
    renderPage();

    await pickProduct(user);

    const row = await screen.findByTestId('variant-row-SKU-RED-L');
    expect(within(row).getByText('9,000원')).toBeInTheDocument();
    expect(within(row).queryByText('12,000원')).not.toBeInTheDocument();
  });

  it('옵션이 없는 상품은 빈 목록이라고 적는다', async () => {
    variants.list.mockResolvedValue([] as never);
    const user = userEvent.setup();
    renderPage();

    await pickProduct(user);

    expect(await screen.findByTestId('variant-empty')).toBeInTheDocument();
  });

  it('차감 버튼 한 번으로는 재고가 줄지 않는다', async () => {
    const user = userEvent.setup();
    renderPage();
    await pickProduct(user);

    await user.click(await screen.findByTestId('variant-decrease-SKU-RED-L'));

    expect(await screen.findByTestId('variant-decrease-confirm')).toBeInTheDocument();
    expect(variants.decreaseStock).not.toHaveBeenCalled();
  });

  it('확인 문구가 무엇을 몇 개에서 줄이는지 다시 적는다', async () => {
    const user = userEvent.setup();
    renderPage();
    await pickProduct(user);

    await user.click(await screen.findByTestId('variant-decrease-SKU-RED-L'));

    const confirm = await screen.findByTestId('variant-decrease-confirm');
    expect(confirm).toHaveTextContent('SKU-RED-L');
    expect(confirm).toHaveTextContent('3개에서 줄입니다');
  });

  it('확인 후에야 적은 수량만큼 차감한다', async () => {
    variants.decreaseStock.mockResolvedValue(variant({ stockQuantity: 1 }) as never);
    const user = userEvent.setup();
    renderPage();
    await pickProduct(user);

    await user.click(await screen.findByTestId('variant-decrease-SKU-RED-L'));
    const qty = await screen.findByTestId('variant-decrease-qty');
    await user.clear(qty);
    await user.type(qty, '2');
    await user.click(screen.getByTestId('variant-decrease-confirm-btn'));

    await waitFor(() => expect(variants.decreaseStock).toHaveBeenCalledWith(1, 7, 2));
    const row = await screen.findByTestId('variant-row-SKU-RED-L');
    expect(within(row).getByText('1')).toBeInTheDocument();
  });

  it('취소하면 확인 패널이 닫히고 아무것도 부르지 않는다', async () => {
    const user = userEvent.setup();
    renderPage();
    await pickProduct(user);

    await user.click(await screen.findByTestId('variant-decrease-SKU-RED-L'));
    await user.click(await screen.findByRole('button', { name: '취소' }));

    await waitFor(() => expect(screen.queryByTestId('variant-decrease-confirm')).not.toBeInTheDocument());
    expect(variants.decreaseStock).not.toHaveBeenCalled();
  });

  it('SKU·옵션명을 채우기 전에는 만들기 버튼이 잠겨 있다', async () => {
    const user = userEvent.setup();
    renderPage();
    await pickProduct(user);

    expect(await screen.findByTestId('variant-create')).toBeDisabled();

    await user.type(screen.getByTestId('variant-sku'), 'SKU-BLUE-M');
    expect(screen.getByTestId('variant-create')).toBeDisabled();

    await user.type(screen.getByTestId('variant-optionName'), '파랑/M');
    expect(screen.getByTestId('variant-create')).toBeEnabled();
  });

  it('추가금을 비우면 0 이 아니라 null 로 보낸다', async () => {
    variants.create.mockResolvedValue(
      variant({ id: 8, sku: 'SKU-BLUE-M', optionName: '파랑/M', additionalPrice: null }) as never,
    );
    const user = userEvent.setup();
    renderPage();
    await pickProduct(user);

    await user.type(await screen.findByTestId('variant-sku'), 'SKU-BLUE-M');
    await user.type(screen.getByTestId('variant-optionName'), '파랑/M');
    await user.click(screen.getByTestId('variant-create'));

    await waitFor(() => expect(variants.create).toHaveBeenCalledWith(1, {
      sku: 'SKU-BLUE-M',
      optionName: '파랑/M',
      additionalPrice: null,
      initialStock: 0,
    }));
  });

  it('만든 옵션은 다시 부르지 않고 표에 이어 붙는다', async () => {
    variants.create.mockResolvedValue(
      variant({ id: 8, sku: 'SKU-BLUE-M', optionName: '파랑/M' }) as never,
    );
    const user = userEvent.setup();
    renderPage();
    await pickProduct(user);

    await user.type(await screen.findByTestId('variant-sku'), 'SKU-BLUE-M');
    await user.type(screen.getByTestId('variant-optionName'), '파랑/M');
    await user.click(screen.getByTestId('variant-create'));

    expect(await screen.findByTestId('variant-row-SKU-BLUE-M')).toBeInTheDocument();
    expect(variants.list).toHaveBeenCalledTimes(1);
  });

  it('해석에는 채운 축만 보낸다 — 빈 줄을 보내면 서버가 못 찾는다', async () => {
    variants.resolve.mockResolvedValue(variant() as never);
    const user = userEvent.setup();
    renderPage();
    await pickProduct(user);

    await user.type(await screen.findByTestId('variant-selection-name-0'), '색상');
    await user.type(screen.getByTestId('variant-selection-value-0'), '빨강');
    await user.click(screen.getByTestId('variant-selection-add'));
    await user.click(screen.getByTestId('variant-resolve'));

    await waitFor(() => expect(variants.resolve).toHaveBeenCalledWith(1, [
      { name: '색상', value: '빨강' },
    ]));
    expect(await screen.findByTestId('variant-resolved')).toHaveTextContent('SKU-RED-L');
  });

  it('해석에 실패하면 사유를 적고 옛 결과를 남기지 않는다', async () => {
    variants.resolve
      .mockResolvedValueOnce(variant() as never)
      .mockRejectedValueOnce(new Error('no match'));
    const user = userEvent.setup();
    renderPage();
    await pickProduct(user);

    await user.type(await screen.findByTestId('variant-selection-name-0'), '색상');
    await user.type(screen.getByTestId('variant-selection-value-0'), '빨강');
    await user.click(screen.getByTestId('variant-resolve'));
    expect(await screen.findByTestId('variant-resolved')).toBeInTheDocument();

    await user.click(screen.getByTestId('variant-resolve'));

    expect(await screen.findByTestId('variant-resolve-error')).toBeInTheDocument();
    expect(screen.queryByTestId('variant-resolved')).not.toBeInTheDocument();
  });

  it('옵션 목록을 못 불러오면 사유를 띄운다', async () => {
    variants.list.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    renderPage();

    await user.selectOptions(await screen.findByTestId('variant-product'), '1');

    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  describe('매입가·마진', () => {
    it('매입가를 모르는 SKU 는 0%가 아니라 빈칸으로 남는다', async () => {
      costs.list.mockResolvedValue([
        cost({ purchasePrice: null, marginAmount: null, marginRate: null }),
      ] as never);
      const user = userEvent.setup();
      renderPage();
      await pickProduct(user);

      const row = await screen.findByTestId('variant-cost-row-SKU-RED-L');
      // 0원·0% 로 그리면 "원가 0 에 다 남는 장사"라는 거짓이 표에 남는다.
      expect(within(row).queryByText('0원')).not.toBeInTheDocument();
      expect(within(row).queryByText('0.00%')).not.toBeInTheDocument();
      expect(within(row).getAllByText('—')).toHaveLength(2);
    });

    it('마진과 마진율을 그대로 그린다', async () => {
      const user = userEvent.setup();
      renderPage();
      await pickProduct(user);

      const row = await screen.findByTestId('variant-cost-row-SKU-RED-L');
      expect(within(row).getByText('5,000원')).toBeInTheDocument();
      expect(within(row).getByText('41.67%')).toBeInTheDocument();
    });

    it('역마진은 감추지 않는다 — 음수 그대로 적는다', async () => {
      costs.list.mockResolvedValue([
        cost({ purchasePrice: 15000, marginAmount: -3000, marginRate: -25 }),
      ] as never);
      const user = userEvent.setup();
      renderPage();
      await pickProduct(user);

      const row = await screen.findByTestId('variant-cost-row-SKU-RED-L');
      expect(within(row).getByText('-3,000원')).toBeInTheDocument();
      expect(within(row).getByText('-25.00%')).toBeInTheDocument();
    });

    it('빈 칸으로 저장하면 0 이 아니라 null 을 보낸다 — 지우기와 0원 매입은 다른 사실이다', async () => {
      costs.setPurchasePrice.mockResolvedValue(
        cost({ purchasePrice: null, marginAmount: null, marginRate: null }) as never,
      );
      const user = userEvent.setup();
      renderPage();
      await pickProduct(user);

      await user.clear(await screen.findByTestId('variant-cost-input-SKU-RED-L'));
      await user.click(screen.getByTestId('variant-cost-save-SKU-RED-L'));

      await waitFor(() =>
        expect(costs.setPurchasePrice).toHaveBeenCalledWith(1, 7, null));
    });

    it('입력한 매입가를 저장하고 돌아온 마진으로 표를 갱신한다', async () => {
      costs.setPurchasePrice.mockResolvedValue(
        cost({ purchasePrice: 9000, marginAmount: 3000, marginRate: 25 }) as never,
      );
      const user = userEvent.setup();
      renderPage();
      await pickProduct(user);

      const input = await screen.findByTestId('variant-cost-input-SKU-RED-L');
      await user.clear(input);
      await user.type(input, '9000');
      await user.click(screen.getByTestId('variant-cost-save-SKU-RED-L'));

      await waitFor(() =>
        expect(costs.setPurchasePrice).toHaveBeenCalledWith(1, 7, 9000));
      const row = await screen.findByTestId('variant-cost-row-SKU-RED-L');
      await waitFor(() => expect(within(row).getByText('25.00%')).toBeInTheDocument());
    });

    it('음수 매입가는 서버까지 가지 않는다', async () => {
      const user = userEvent.setup();
      renderPage();
      await pickProduct(user);

      const input = await screen.findByTestId('variant-cost-input-SKU-RED-L');
      await user.clear(input);
      await user.type(input, '-100');
      await user.click(screen.getByTestId('variant-cost-save-SKU-RED-L'));

      expect(costs.setPurchasePrice).not.toHaveBeenCalled();
    });

    it('매입가 조회가 막혀도 옵션 목록은 그대로 보인다', async () => {
      costs.list.mockRejectedValue(new Error('forbidden'));
      const user = userEvent.setup();
      renderPage();
      await pickProduct(user);

      expect(await screen.findByTestId('variant-cost-error')).toBeInTheDocument();
      expect(await screen.findByTestId('variant-row-SKU-RED-L')).toBeInTheDocument();
    });
  });
});
