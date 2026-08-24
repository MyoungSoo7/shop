import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { CartProvider } from '@/contexts/CartContext';
import { useCart } from '@/contexts/useCart';
import { AuthContext, AuthContextType } from '@/contexts/useAuth';
import { cartApi } from '@/api/cart';
import { productApi } from '@/api/product';
import { ProductResponse } from '@/types';

vi.mock('@/api/cart', () => ({
  cartApi: {
    get: vi.fn(),
    addItem: vi.fn(),
    changeQuantity: vi.fn(),
    removeItem: vi.fn(),
    clear: vi.fn(),
    checkout: vi.fn(),
  },
}));

vi.mock('@/api/product', () => ({
  productApi: { getProduct: vi.fn() },
}));

const product = (id: number, price: number): ProductResponse => ({
  id,
  name: `상품${id}`,
  price,
  stockQuantity: 99,
  status: 'ACTIVE',
  availableForSale: true,
  createdAt: '2026-08-01T00:00:00',
  updatedAt: '2026-08-01T00:00:00',
});

const authValue = (userId: number | null, loading = false): AuthContextType => ({
  user: userId == null ? null : {
    id: userId, email: 'u@example.com', role: 'USER',
    name: null, phoneNumber: null, active: true, createdAt: '2026-08-01T00:00:00',
  },
  userId,
  loading,
  refresh: vi.fn(),
});

const Consumer = () => {
  const { items, addItem, removeItem, updateQuantity, clearCart, totalCount, totalAmount, serverBacked } = useCart();
  return (
    <div>
      <span data-testid="count">{totalCount}</span>
      <span data-testid="amount">{totalAmount}</span>
      <span data-testid="mode">{serverBacked ? 'server' : 'local'}</span>
      <ul>
        {items.map((i) => (
          <li key={i.product.id}>{`${i.product.name}:${i.quantity}`}</li>
        ))}
      </ul>
      <button onClick={() => addItem(product(100, 1000))}>add</button>
      <button onClick={() => updateQuantity(100, 5)}>qty5</button>
      <button onClick={() => removeItem(100)}>remove</button>
      <button onClick={() => clearCart()}>clear</button>
    </div>
  );
};

const renderWith = (auth: AuthContextType) =>
  render(
    <AuthContext.Provider value={auth}>
      <CartProvider>
        <Consumer />
      </CartProvider>
    </AuthContext.Provider>
  );

describe('CartContext', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    localStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
  });

  describe('비로그인 (로컬 모드)', () => {
    it('localStorage 에 저장하고 서버를 부르지 않는다', async () => {
      renderWith(authValue(null));

      expect(screen.getByTestId('mode')).toHaveTextContent('local');
      fireEvent.click(screen.getByRole('button', { name: 'add' }));

      expect(screen.getByTestId('count')).toHaveTextContent('1');
      await waitFor(() =>
        expect(JSON.parse(localStorage.getItem('lemuel_cart') ?? '[]')).toHaveLength(1)
      );
      expect(cartApi.addItem).not.toHaveBeenCalled();
    });

    it('수량 0 은 삭제로 취급한다', () => {
      renderWith(authValue(null));
      fireEvent.click(screen.getByRole('button', { name: 'add' }));
      expect(screen.getByTestId('count')).toHaveTextContent('1');

      fireEvent.click(screen.getByRole('button', { name: 'remove' }));
      expect(screen.getByTestId('count')).toHaveTextContent('0');
    });
  });

  describe('로그인 (서버 모드)', () => {
    it('서버 항목에 상품 상세를 붙여 보여준다', async () => {
      vi.mocked(cartApi.get).mockResolvedValue({
        cart: { id: 1, userId: 7, totalQuantity: 2, itemCount: 1, lastActiveAt: null },
        items: [{ id: 1, productId: 100, variantId: null, quantity: 2, addedAt: null }],
      });
      vi.mocked(productApi.getProduct).mockResolvedValue(product(100, 1500));

      renderWith(authValue(7));

      await waitFor(() => expect(screen.getByText('상품100:2')).toBeInTheDocument());
      expect(screen.getByTestId('mode')).toHaveTextContent('server');
      // 가격은 장바구니가 아니라 상품에서 온다 — 2 x 1500
      expect(screen.getByTestId('amount')).toHaveTextContent('3000');
    });

    /**
     * 이 병합이 없으면 "담아두고 로그인했더니 장바구니가 비어 있다"가 된다.
     * 로컬을 지우지 않으면 로그아웃 때 유령 항목으로 되살아난다.
     */
    it('로그인 시 로컬 항목을 서버로 병합하고 로컬을 비운다', async () => {
      localStorage.setItem(
        'lemuel_cart',
        JSON.stringify([{ product: product(100, 1000), quantity: 3 }])
      );
      vi.mocked(cartApi.addItem).mockResolvedValue({
        cart: { id: 1, userId: 7, totalQuantity: 3, itemCount: 1, lastActiveAt: null },
        items: [],
      });
      vi.mocked(cartApi.get).mockResolvedValue({
        cart: { id: 1, userId: 7, totalQuantity: 3, itemCount: 1, lastActiveAt: null },
        items: [{ id: 1, productId: 100, variantId: null, quantity: 3, addedAt: null }],
      });
      vi.mocked(productApi.getProduct).mockResolvedValue(product(100, 1000));

      renderWith(authValue(7));

      await waitFor(() => expect(cartApi.addItem).toHaveBeenCalledWith(7, 100, 3));
      await waitFor(() => expect(localStorage.getItem('lemuel_cart')).toBeNull());
      await waitFor(() => expect(screen.getByText('상품100:3')).toBeInTheDocument());
    });

    it('담기·수량변경·삭제·비우기가 각각 서버에 반영된다', async () => {
      const empty = {
        cart: { id: 1, userId: 7, totalQuantity: 0, itemCount: 0, lastActiveAt: null },
        items: [],
      };
      vi.mocked(cartApi.get).mockResolvedValue(empty);
      vi.mocked(cartApi.addItem).mockResolvedValue(empty);
      vi.mocked(cartApi.changeQuantity).mockResolvedValue(empty);
      vi.mocked(cartApi.removeItem).mockResolvedValue(empty);
      vi.mocked(cartApi.clear).mockResolvedValue(empty);

      renderWith(authValue(7));
      await waitFor(() => expect(cartApi.get).toHaveBeenCalled());

      fireEvent.click(screen.getByRole('button', { name: 'add' }));
      await waitFor(() => expect(cartApi.addItem).toHaveBeenCalledWith(7, 100, 1));

      fireEvent.click(screen.getByRole('button', { name: 'qty5' }));
      await waitFor(() => expect(cartApi.changeQuantity).toHaveBeenCalledWith(7, 100, 5));

      fireEvent.click(screen.getByRole('button', { name: 'remove' }));
      await waitFor(() => expect(cartApi.removeItem).toHaveBeenCalledWith(7, 100));

      fireEvent.click(screen.getByRole('button', { name: 'clear' }));
      await waitFor(() => expect(cartApi.clear).toHaveBeenCalledWith(7));
    });

    /** 로그인 상태에서 로컬에도 계속 쓰면 로그아웃 후 남의 장바구니가 남는다. */
    it('서버 모드에서는 localStorage 를 정본으로 쓰지 않는다', async () => {
      vi.mocked(cartApi.get).mockResolvedValue({
        cart: { id: 1, userId: 7, totalQuantity: 0, itemCount: 0, lastActiveAt: null },
        items: [],
      });
      vi.mocked(cartApi.addItem).mockResolvedValue({
        cart: { id: 1, userId: 7, totalQuantity: 1, itemCount: 1, lastActiveAt: null },
        items: [],
      });

      renderWith(authValue(7));
      await waitFor(() => expect(cartApi.get).toHaveBeenCalled());

      fireEvent.click(screen.getByRole('button', { name: 'add' }));
      await waitFor(() => expect(cartApi.addItem).toHaveBeenCalled());

      expect(localStorage.getItem('lemuel_cart')).toBeNull();
    });

    /** 담아둔 것을 화면에서 지우는 쪽이 더 나쁜 손실이다. */
    it('서버 조회가 실패해도 로컬 항목을 지우지 않는다', async () => {
      localStorage.setItem(
        'lemuel_cart',
        JSON.stringify([{ product: product(100, 1000), quantity: 2 }])
      );
      vi.mocked(cartApi.addItem).mockRejectedValue(new Error('boom'));
      vi.mocked(cartApi.get).mockRejectedValue(new Error('boom'));

      renderWith(authValue(7));

      await waitFor(() => expect(cartApi.addItem).toHaveBeenCalled());
      expect(await screen.findByText('상품100:2')).toBeInTheDocument();
    });

    it('상품 상세 조회가 실패한 항목만 버리고 나머지는 보여준다', async () => {
      vi.mocked(cartApi.get).mockResolvedValue({
        cart: { id: 1, userId: 7, totalQuantity: 2, itemCount: 2, lastActiveAt: null },
        items: [
          { id: 1, productId: 100, variantId: null, quantity: 1, addedAt: null },
          { id: 2, productId: 999, variantId: null, quantity: 1, addedAt: null },
        ],
      });
      vi.mocked(productApi.getProduct).mockImplementation(async (id: number) => {
        if (id === 999) throw new Error('deleted');
        return product(id, 1000);
      });

      renderWith(authValue(7));

      await waitFor(() => expect(screen.getByText('상품100:1')).toBeInTheDocument());
      expect(screen.queryByText('상품999:1')).not.toBeInTheDocument();
    });
  });

  it('Provider 밖에서 useCart 를 쓰면 예외가 발생한다', () => {
    const Broken = () => {
      useCart();
      return null;
    };
    expect(() => render(<Broken />)).toThrow('useCart must be used within CartProvider');
  });
});
