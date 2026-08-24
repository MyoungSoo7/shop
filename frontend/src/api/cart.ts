import api from './axios';

/**
 * 서버 장바구니 — order-service {@code CartController} (/users/{userId}/cart).
 *
 * <p>서버 응답은 <b>상품 상세를 담지 않는다</b>(productId·variantId·quantity 뿐).
 * 이름·가격·재고는 상품 API 로 따로 읽어 합쳐야 한다 — 장바구니가 상품 정보를 복제해
 * 저장하면 가격 변경이 반영되지 않기 때문이며, 이 경계는 서버 설계 그대로다.
 *
 * <p>모든 경로가 {@code ResourceOwnership.requireSelfOrAdmin} 로 보호되므로
 * userId 는 반드시 로그인 주체({@code GET /users/me})에서 온 값이어야 한다.
 */

/** 장바구니 메타 — 항목 수·총수량. */
export interface CartMeta {
  id: number;
  userId: number;
  totalQuantity: number;
  itemCount: number;
  lastActiveAt: string | null;
}

/** 서버 장바구니 항목 — 상품 상세 없음(productId 로 하이드레이션 필요). */
export interface ServerCartItem {
  id: number;
  productId: number;
  variantId: number | null;
  quantity: number;
  addedAt: string | null;
}

export interface CartResponse {
  cart: CartMeta;
  items: ServerCartItem[];
}

/** 체크아웃 결과 — 장바구니가 주문 1건으로 변환된다. */
export interface CheckoutResponse {
  orderId: number;
  amount: number;
  itemCount: number;
  status: string;
}

const base = (userId: number) => `/users/${userId}/cart`;

export const cartApi = {
  /** GET — 없으면 서버가 자동 생성한다. */
  get: async (userId: number): Promise<CartResponse> => {
    const response = await api.get<CartResponse>(base(userId));
    return response.data;
  },

  /** POST /items — 같은 (productId, variantId) 면 서버가 수량 증가로 변환한다. */
  addItem: async (
    userId: number,
    productId: number,
    quantity: number,
    variantId?: number | null
  ): Promise<CartResponse> => {
    const response = await api.post<CartResponse>(`${base(userId)}/items`, {
      productId,
      variantId: variantId ?? null,
      quantity,
    });
    return response.data;
  },

  /** PATCH /items — quantity 0 이면 서버가 삭제로 처리한다. */
  changeQuantity: async (
    userId: number,
    productId: number,
    quantity: number,
    variantId?: number | null
  ): Promise<CartResponse> => {
    const response = await api.patch<CartResponse>(`${base(userId)}/items`, {
      productId,
      variantId: variantId ?? null,
      quantity,
    });
    return response.data;
  },

  /** DELETE /items?productId=&variantId= */
  removeItem: async (
    userId: number,
    productId: number,
    variantId?: number | null
  ): Promise<CartResponse> => {
    const response = await api.delete<CartResponse>(`${base(userId)}/items`, {
      params: variantId == null ? { productId } : { productId, variantId },
    });
    return response.data;
  },

  /** DELETE — 장바구니 비우기. */
  clear: async (userId: number): Promise<CartResponse> => {
    const response = await api.delete<CartResponse>(base(userId));
    return response.data;
  },

  /** POST /checkout — 재고 차감 + 주문 생성 + 장바구니 clear (실패 시 장바구니 유지). */
  checkout: async (userId: number): Promise<CheckoutResponse> => {
    const response = await api.post<CheckoutResponse>(`${base(userId)}/checkout`);
    return response.data;
  },
};
