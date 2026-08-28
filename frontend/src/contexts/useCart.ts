import { createContext, useContext } from 'react';
import { ProductResponse } from '@/types';

/**
 * 장바구니 컨텍스트와 소비 훅 — 컴포넌트(CartProvider)와 <b>파일을 분리</b>한다.
 *
 * <p>이유는 {@link ./useToast} 와 같다: 컴포넌트 파일에서 컴포넌트 외의 값을 export 하면
 * Fast Refresh 가 상태를 잃는다(react-refresh/only-export-components).
 */
export interface CartItem {
  product: ProductResponse;
  quantity: number;
  /**
   * 고른 SKU. 옵션 없는 상품은 null 이다.
   *
   * <p>이 필드가 없던 동안 장바구니 항목의 열쇠는 상품 id 하나였다. 그래서 같은 티셔츠의
   * 빨강/L 과 파랑/M 이 한 줄로 합쳐졌고, 서버로 나가는 것도 옵션 없는 항목이었다 —
   * 재고는 SKU 에 붙어 있으므로 그 주문은 어느 재고도 깎지 않는다.
   * 서버 장바구니는 처음부터 (productId, variantId) 를 열쇠로 쓰고 있었다(중복은 수량 증가로
   * 흡수한다). 화면만 그 열쇠를 절반만 들고 있었던 것이다.
   */
  variantId?: number | null;
  /** 사람이 읽는 옵션 표시("빨강 / L"). 서버 값이 아니라 고를 때 만든 라벨이다. */
  optionLabel?: string | null;
}

export interface CartContextType {
  items: CartItem[];
  addItem: (product: ProductResponse, variantId?: number | null, optionLabel?: string | null) => void;
  removeItem: (productId: number, variantId?: number | null) => void;
  updateQuantity: (productId: number, quantity: number, variantId?: number | null) => void;
  clearCart: () => void;
  totalAmount: number;
  totalCount: number;
  /**
   * 서버 장바구니를 읽어오는 중. 비로그인(로컬 모드)에서는 항상 false 다.
   * 이 값이 true 인 동안의 빈 목록은 "장바구니가 비었다"가 아니라 "아직 모른다"이다.
   */
  loading: boolean;
  /** 로그인 상태에서 서버와 통신 중인지 — 저장은 낙관적으로 반영되므로 표시용이다. */
  syncing: boolean;
  /** 서버 장바구니 사용 여부(로그인 + userId 확보). false 면 localStorage 로 동작한다. */
  serverBacked: boolean;
}

export const CartContext = createContext<CartContextType | null>(null);

export const useCart = (): CartContextType => {
  const ctx = useContext(CartContext);
  if (!ctx) throw new Error('useCart must be used within CartProvider');
  return ctx;
};
