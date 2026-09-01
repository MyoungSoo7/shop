import api from './axios';

/**
 * 상품 옵션(SKU) — order-service {@code ProductVariantController}.
 *
 * <p><b>왜 생겼나.</b> 옵션 축과 값을 정의하는 콘솔은 있었다({@code /admin/system/option-catalog}).
 * 하지만 그건 "색상에는 빨강·파랑이 있다"는 <b>사전</b>이고, "이 상품의 빨강/L 은 재고 3개,
 * 추가금 2,000원"이라는 <b>실물</b>을 만드는 화면은 없었다. 그래서 SKU 를 만드는 길은
 * {@code curl} 뿐이었고, 재고가 어긋나면 DB 를 직접 고쳤다.
 *
 * <h3>응답이 {@code {"variant": {...}}} 로 한 겹 싸여 있다</h3>
 * <p>서버가 필드 순서를 보존하면서 <b>null 을 허용</b>하려고 맵으로 담아 내보내는 구조라,
 * 응답이 평평한 객체가 아니다. 화면이 이 껍질을 안 벗기면 모든 필드가 {@code undefined} 로
 * 조용히 비어 렌더된다 — 에러가 아니라 <b>빈 표</b>가 되므로 눈에 잘 안 띈다. 벗기는 자리를
 * 여기 한 곳으로 모은다.
 *
 * <h3>재고 차감은 화면이 함부로 부를 것이 아니다</h3>
 * <p>{@link productVariantApi.decreaseStock} 은 주문이 프로세스 안에서 부르는 것과 <b>같은</b>
 * 유스케이스를 HTTP 로 여는 문이다. 즉 이 버튼을 누르면 주문 없이 재고가 준다. 정정·폐기처럼
 * 실물이 사라진 경우를 반영하는 운영자 조작이고, 서버도 ADMIN 으로만 연다
 * ({@code SecurityConfig} 의 {@code POST /products/*&#47;variants/*&#47;decrease-stock}).
 * 되돌리는 API 는 없다 — 늘리려면 SKU 를 다시 만드는 수밖에 없다.
 */

export type VariantStatus = string;

/** 껍질을 벗긴 SKU 한 줄. 할인 두 필드는 미설정이 정상이다(대부분의 SKU 가 할인 없음). */
export interface ProductVariant {
  id: number;
  productId: number;
  sku: string;
  optionName: string;
  additionalPrice: number | null;
  discountPrice: number | null;
  discountRate: number | null;
  stockQuantity: number;
  /** 낙관적 락 버전. 화면은 읽기만 한다 — 충돌 재시도는 서버가 한다. */
  version: number;
  status: VariantStatus;
  /** 옵션 트리 선택 경로를 정규화한 서명. 트리에서 SKU 를 되찾는 열쇠다. */
  optionSignature: string | null;
}

/** 서버가 내보내는 실제 모양. 화면은 이 타입을 밖으로 내보내지 않는다. */
interface VariantEnvelope {
  variant: ProductVariant;
}

export interface CreateVariantRequest {
  sku: string;
  optionName: string;
  /** 기본가에 더할 금액. 비워 두면 서버가 0 으로 본다. */
  additionalPrice?: number | null;
  initialStock: number;
}

/** 옵션 트리에서 고른 한 칸. 이름과 값이 둘 다 서버의 정의와 맞아야 해석된다. */
export interface OptionSelection {
  name: string;
  value: string;
}

const unwrap = (envelope: VariantEnvelope): ProductVariant => envelope.variant;

// 경로는 전체 리터럴로 적는다. 조각을 이어 붙이면 사람 눈에도, 저장소의 화면-API 대조
// 게이트(api-screen-gate)에도 어떤 엔드포인트를 부르는지 보이지 않는다.
export const productVariantApi = {
  /** GET — 그 상품의 SKU 전부. */
  list: async (productId: number): Promise<ProductVariant[]> =>
    (await api.get<VariantEnvelope[]>(`/products/${productId}/variants`)).data.map(unwrap),

  /** POST — SKU 생성. sku 는 상품 안에서 유일해야 한다(중복이면 서버가 거절). */
  create: async (productId: number, body: CreateVariantRequest): Promise<ProductVariant> =>
    unwrap((await api.post<VariantEnvelope>(`/products/${productId}/variants`, body)).data),

  /** POST — 재고 차감. 주문 없이 재고가 줄어드는 조작이라 되돌릴 수 없다. */
  decreaseStock: async (
    productId: number,
    variantId: number,
    quantity: number,
  ): Promise<ProductVariant> =>
    unwrap((await api.post<VariantEnvelope>(
      `/products/${productId}/variants/${variantId}/decrease-stock`,
      { quantity },
    )).data),

  /** POST — 옵션 선택 경로를 SKU 하나로 해석한다. 주문이 variantId 를 얻는 길과 같은 경로다. */
  resolve: async (productId: number, selections: OptionSelection[]): Promise<ProductVariant> =>
    unwrap((await api.post<VariantEnvelope>(
      `/products/${productId}/variants/resolve`,
      { selections },
    )).data),
};

/**
 * SKU 한 줄의 원가와 마진 — order-service {@code AdminVariantCostController}.
 *
 * <p><b>왜 위 {@link ProductVariant} 에 필드로 붙이지 않았나.</b> 그 타입은 구매자가 부르는
 * {@code POST /products/{id}/variants/resolve} 의 응답이기도 하다. 거기에 원가를 얹으면
 * 로그인한 아무나 원가를 본다. 서버가 응답을 갈라 놓았으므로 화면 타입도 갈라 둔다 —
 * 한쪽으로 합치는 순간 그 사실이 보이지 않게 된다.
 *
 * <p>{@code marginAmount}·{@code marginRate} 가 {@code null} 인 것은 마진이 0 이라는 뜻이
 * 아니라 <b>매입가를 아직 모른다</b>는 뜻이다. 화면은 이 둘을 다르게 그려야 한다.
 */
export interface VariantCost {
  variantId: number;
  sku: string;
  optionName: string;
  stockQuantity: number;
  /** 저장된 값이 아니라 기준가+추가금-할인으로 서버가 계산한 값. */
  sellingPrice: number;
  /** null 은 미입력. 0 은 "0원에 샀다"로 다른 뜻이다. */
  purchasePrice: number | null;
  /** 판매가 - 매입가. 역마진이면 음수 그대로 온다. */
  marginAmount: number | null;
  /** 판매가 대비 마진율(매출총이익률, %). 매입가 대비 가산율이 아니다. */
  marginRate: number | null;
}

export const variantCostApi = {
  /** GET — 그 상품 SKU 들의 원가·마진. 관리자 전용 경로다. */
  list: async (productId: number): Promise<VariantCost[]> =>
    (await api.get<VariantCost[]>(`/admin/products/${productId}/variants/costs`)).data,

  /** PUT — 매입가 설정. null 을 보내면 "모른다"로 되돌린다(0 으로 덮는 것과 다르다). */
  setPurchasePrice: async (
    productId: number,
    variantId: number,
    purchasePrice: number | null,
  ): Promise<VariantCost> =>
    (await api.put<VariantCost>(
      `/admin/products/${productId}/variants/${variantId}/purchase-price`,
      { purchasePrice },
    )).data,
};

/**
 * 화면에 보일 판매가 — 기본가 + 추가금, 할인가가 있으면 그것.
 *
 * <p>할인가는 "기본가에서 얼마를 뺀 값"이 아니라 <b>확정된 판매가</b>다. 그래서 있으면
 * 추가금을 더하지 않고 그대로 쓴다. 두 값을 겹쳐 계산하면 화면의 금액이 결제 금액과 갈린다.
 */
export function effectivePrice(basePrice: number, variant: ProductVariant): number {
  if (variant.discountPrice != null) return variant.discountPrice;
  return basePrice + (variant.additionalPrice ?? 0);
}
