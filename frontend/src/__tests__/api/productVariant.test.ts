import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  effectivePrice,
  productVariantApi,
  type ProductVariant,
} from '@/api/productVariant';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const variant = (over: Partial<ProductVariant> = {}): ProductVariant => ({
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

describe('productVariantApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  /**
   * 서버가 필드 순서를 보존하며 null 을 허용하려고 맵으로 담아 내보내는 구조라, 응답이
   * 평평한 객체가 아니다. 껍질을 안 벗기면 모든 필드가 undefined 로 <b>조용히</b> 비어 렌더된다
   * — 에러가 아니라 빈 표가 되므로 눈에 잘 안 띈다.
   */
  it('단건 응답의 variant 껍질을 벗긴다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { variant: variant() } });

    const created = await productVariantApi.create(1, {
      sku: 'SKU-RED-L', optionName: '빨강/L', additionalPrice: 2000, initialStock: 3,
    });

    expect(created.sku).toBe('SKU-RED-L');
    expect(created).not.toHaveProperty('variant');
  });

  it('목록 응답도 줄마다 껍질을 벗긴다', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: [{ variant: variant() }, { variant: variant({ id: 8, sku: 'SKU-BLUE-M' }) }],
    });

    const list = await productVariantApi.list(1);

    expect(api.get).toHaveBeenCalledWith('/products/1/variants');
    expect(list.map((v) => v.sku)).toEqual(['SKU-RED-L', 'SKU-BLUE-M']);
  });

  it('할인 두 필드는 null 로 와도 그대로 남는다 — 0 으로 접으면 "할인 없음"과 "0원 할인"이 섞인다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [{ variant: variant() }] });

    const [only] = await productVariantApi.list(1);

    expect(only.discountPrice).toBeNull();
    expect(only.discountRate).toBeNull();
  });

  it('재고 차감은 상품·옵션 두 식별자를 경로에 담고 수량만 본문으로 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { variant: variant({ stockQuantity: 1 }) } });

    const updated = await productVariantApi.decreaseStock(1, 7, 2);

    expect(api.post).toHaveBeenCalledWith('/products/1/variants/7/decrease-stock', { quantity: 2 });
    expect(updated.stockQuantity).toBe(1);
  });

  /** 주문이 선택 경로를 variantId 로 바꾸는 것과 같은 경로다. */
  it('옵션 해석은 선택 목록을 그대로 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: { variant: variant() } });

    const resolved = await productVariantApi.resolve(1, [{ name: '색상', value: '빨강' }]);

    expect(api.post).toHaveBeenCalledWith('/products/1/variants/resolve', {
      selections: [{ name: '색상', value: '빨강' }],
    });
    expect(resolved.id).toBe(7);
  });
});

describe('effectivePrice', () => {
  /**
   * 할인가는 "기본가에서 얼마를 뺀 값"이 아니라 확정된 판매가다. 두 값을 겹쳐 계산하면
   * 화면의 금액이 결제 금액과 갈린다.
   */
  it('할인가가 있으면 그것이 판매가다 — 추가금을 겹쳐 더하지 않는다', () => {
    expect(effectivePrice(10000, variant({ additionalPrice: 2000, discountPrice: 9000 }))).toBe(9000);
  });

  it('할인가가 없으면 기본가 + 추가금', () => {
    expect(effectivePrice(10000, variant({ additionalPrice: 2000 }))).toBe(12000);
  });

  it('추가금이 미설정이면 기본가 그대로다', () => {
    expect(effectivePrice(10000, variant({ additionalPrice: null }))).toBe(10000);
  });
});
