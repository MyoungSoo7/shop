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
}

export interface CartContextType {
  items: CartItem[];
  addItem: (product: ProductResponse) => void;
  removeItem: (productId: number) => void;
  updateQuantity: (productId: number, quantity: number) => void;
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
