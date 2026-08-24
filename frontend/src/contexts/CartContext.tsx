import React, { useCallback, useEffect, useRef, useState } from 'react';
import { CartContext, CartItem } from '@/contexts/useCart';
import { useAuth } from '@/contexts/useAuth';
import { cartApi, ServerCartItem } from '@/api/cart';
import { productApi } from '@/api/product';
import { ProductResponse } from '@/types';

const STORAGE_KEY = 'lemuel_cart';

const readLocal = (): CartItem[] => {
  try {
    const stored = localStorage.getItem(STORAGE_KEY);
    return stored ? (JSON.parse(stored) as CartItem[]) : [];
  } catch {
    return [];
  }
};

/**
 * 서버 항목(productId·quantity)에 상품 상세를 붙인다.
 *
 * <p>서버 장바구니는 이름·가격을 저장하지 않는다 — 저장했다면 가격이 바뀌어도 장바구니에는
 * 옛 가격이 남는다. 그래서 매 조회마다 상품을 다시 읽는다. 항목 수만큼 요청이 나가지만
 * 장바구니는 수십 건 규모라 수용 가능하고, 한 건이 실패해도 그 항목만 버리고 나머지를 보여준다
 * (품절·삭제된 상품 때문에 장바구니 전체가 안 열리는 쪽이 더 나쁘다).
 */
const hydrate = async (serverItems: ServerCartItem[]): Promise<CartItem[]> => {
  const settled = await Promise.allSettled(
    serverItems.map(async (item) => ({
      product: await productApi.getProduct(item.productId),
      quantity: item.quantity,
    }))
  );
  return settled
    .filter((r): r is PromiseFulfilledResult<CartItem> => r.status === 'fulfilled')
    .map((r) => r.value);
};

/**
 * 장바구니 상태 공급자 — 로그인 여부에 따라 저장소가 바뀐다.
 *
 * <ul>
 *   <li><b>비로그인</b>: localStorage. 서버에 붙을 사용자가 없으니 종전 동작 그대로다.
 *   <li><b>로그인</b>: order-service 장바구니. 기기를 바꿔도 따라온다.
 * </ul>
 *
 * <p>로그인하는 순간 로컬에 있던 항목을 서버로 <b>병합</b>하고 로컬을 비운다. 병합하지 않으면
 * "담아두고 로그인했더니 장바구니가 비어 있다"가 되고, 로컬을 남겨두면 로그아웃 때 유령 항목이
 * 되살아난다. 병합은 서버가 (productId, variantId) 중복을 수량 증가로 흡수하므로 add 반복으로 족하다.
 */
export const CartProvider: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const { userId, loading: authLoading } = useAuth();
  const [items, setItems] = useState<CartItem[]>(() => readLocal());
  const [loading, setLoading] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const serverBacked = userId != null;
  /** 이미 병합을 끝낸 userId — 리렌더마다 로컬 항목을 다시 밀어 넣지 않기 위한 표식. */
  const mergedFor = useRef<number | null>(null);

  // 비로그인 동안에만 localStorage 를 정본으로 유지한다. 로그인 상태에서 여기에 계속 쓰면
  // 로그아웃 후 남의 장바구니가 로컬에 남는다.
  useEffect(() => {
    if (!serverBacked) {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
    }
  }, [items, serverBacked]);

  const reload = useCallback(async (uid: number) => {
    const response = await cartApi.get(uid);
    setItems(await hydrate(response.items));
  }, []);

  // 로그인 전환 시: 로컬 → 서버 병합 후 서버 상태로 교체.
  useEffect(() => {
    if (authLoading) return;
    if (userId == null) {
      mergedFor.current = null;
      return;
    }
    if (mergedFor.current === userId) return;
    mergedFor.current = userId;

    let cancelled = false;
    void (async () => {
      setLoading(true);
      try {
        const pending = readLocal();
        for (const item of pending) {
          await cartApi.addItem(userId, item.product.id, item.quantity);
        }
        if (pending.length > 0) localStorage.removeItem(STORAGE_KEY);
        if (!cancelled) await reload(userId);
      } catch {
        // 서버 장바구니를 못 읽으면 로컬 항목을 그대로 보여준다 — 담아둔 것을 화면에서
        // 지우는 것보다, 동기화가 안 됐음을 감수하고 보존하는 편이 사용자 손실이 적다.
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [userId, authLoading, reload]);

  /** 서버 호출을 감싸고, 실패하면 서버 상태로 되돌려 화면과 서버가 어긋난 채 남지 않게 한다. */
  const withServer = useCallback(
    async (uid: number, action: () => Promise<void>) => {
      setSyncing(true);
      try {
        await action();
      } catch {
        try {
          await reload(uid);
        } catch {
          /* 되돌리기까지 실패하면 다음 진입 시 재조회에 맡긴다. */
        }
      } finally {
        setSyncing(false);
      }
    },
    [reload]
  );

  const addItem = useCallback(
    (product: ProductResponse) => {
      // 낙관적 갱신 — 서버 왕복을 기다리며 버튼이 먹통이 되지 않게 한다.
      setItems((prev) => {
        const existing = prev.find((i) => i.product.id === product.id);
        if (existing) {
          return prev.map((i) =>
            i.product.id === product.id
              ? { ...i, quantity: Math.min(i.quantity + 1, product.stockQuantity) }
              : i
          );
        }
        return [...prev, { product, quantity: 1 }];
      });
      if (userId != null) {
        void withServer(userId, () => cartApi.addItem(userId, product.id, 1).then(() => undefined));
      }
    },
    [userId, withServer]
  );

  const removeItem = useCallback(
    (productId: number) => {
      setItems((prev) => prev.filter((i) => i.product.id !== productId));
      if (userId != null) {
        void withServer(userId, () =>
          cartApi.removeItem(userId, productId).then(() => undefined)
        );
      }
    },
    [userId, withServer]
  );

  const updateQuantity = useCallback(
    (productId: number, quantity: number) => {
      if (quantity <= 0) {
        removeItem(productId);
        return;
      }
      setItems((prev) =>
        prev.map((i) => (i.product.id === productId ? { ...i, quantity } : i))
      );
      if (userId != null) {
        void withServer(userId, () =>
          cartApi.changeQuantity(userId, productId, quantity).then(() => undefined)
        );
      }
    },
    [userId, withServer, removeItem]
  );

  const clearCart = useCallback(() => {
    setItems([]);
    if (userId != null) {
      void withServer(userId, () => cartApi.clear(userId).then(() => undefined));
    }
  }, [userId, withServer]);

  const totalAmount = items.reduce((sum, i) => sum + i.product.price * i.quantity, 0);
  const totalCount = items.reduce((sum, i) => sum + i.quantity, 0);

  return (
    <CartContext.Provider
      value={{
        items,
        addItem,
        removeItem,
        updateQuantity,
        clearCart,
        totalAmount,
        totalCount,
        loading,
        syncing,
        serverBacked,
      }}
    >
      {children}
    </CartContext.Provider>
  );
};
