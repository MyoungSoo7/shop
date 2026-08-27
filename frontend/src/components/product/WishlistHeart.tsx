import React, { useCallback, useEffect, useState } from 'react';
import { wishlistApi } from '@/api/wishlist';
import { useAuth } from '@/contexts/useAuth';
import { useToast } from '@/contexts/useToast';

/**
 * 상품 하나의 찜 토글(하트).
 *
 * <p><b>왜 서버 응답만 그리는가.</b> 하트는 연타되는 버튼이다. 낙관적으로 뒤집어 놓고 실패하면
 * 되돌리는 방식은, 두 번째 클릭이 첫 번째 응답보다 먼저 돌아오는 순간 화면과 서버가 어긋난 채
 * 굳는다. 서버의 담기·빼기는 <b>멱등</b>이고 응답이 "방금 한 동작"이 아니라 <b>끝난 뒤의 상태</b>
 * ({@code wished})라, 어느 응답이 마지막으로 오든 그 값을 그대로 그리면 화면은 항상 서버와 같다.
 * 대신 요청 중에는 버튼을 잠가 연타 자체를 줄인다.
 *
 * <p><b>비로그인은 하트를 숨기지 않는다.</b> 숨기면 이 상품을 찜할 수 있다는 사실 자체가 안 보인다.
 * 눌렀을 때 로그인을 안내한다.
 */
interface WishlistHeartProps {
  productId: number;
  /** 목록 화면처럼 상태를 이미 아는 자리에서 넘긴다. 넘기면 단건 조회를 하지 않는다. */
  initialWished?: boolean;
  /** 담긴 개수 등 바깥 표시를 갱신해야 할 때. */
  onChange?: (wished: boolean, count: number) => void;
  className?: string;
}

const WishlistHeart: React.FC<WishlistHeartProps> = ({
  productId,
  initialWished,
  onChange,
  className = '',
}) => {
  const { userId } = useAuth();
  const { showToast } = useToast();
  const [wished, setWished] = useState<boolean>(initialWished ?? false);
  const [pending, setPending] = useState(false);

  useEffect(() => {
    // 이미 아는 상태를 넘겨받았으면 묻지 않는다 — 목록 20줄이 각자 단건 조회를 하면 20번이 된다.
    if (initialWished !== undefined || userId === null) return;

    let alive = true;
    wishlistApi
      .contains(userId, productId)
      .then((result) => {
        if (alive) setWished(result.wished);
      })
      // 하트를 못 읽은 것으로 상품 화면 전체를 실패시키지 않는다. 안 칠해진 채로 둔다.
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [userId, productId, initialWished]);

  const toggle = useCallback(async () => {
    if (userId === null) {
      showToast('로그인하면 찜할 수 있습니다.', 'info');
      return;
    }
    if (pending) return;

    setPending(true);
    try {
      const result = wished
        ? await wishlistApi.remove(userId, productId)
        : await wishlistApi.add(userId, productId);

      setWished(result.wished);
      onChange?.(result.wished, result.count);

      // changed 가 false 면 다른 탭에서 이미 같은 일이 끝나 있었다는 뜻이다. 결과는 사용자가
      // 원한 그대로이므로 실패가 아니고, 토스트를 띄우면 하지도 않은 일을 했다고 말하게 된다.
      if (result.changed) {
        showToast(result.wished ? '찜했습니다.' : '찜에서 뺐습니다.', 'success');
      }
    } catch {
      // 상한 초과 등은 서버가 400 과 사유를 준다. axios 인터셉터가 메시지를 띄우므로 여기서
      // 다시 띄우지 않는다. 상태는 건드리지 않는다 — 서버가 뭘 했는지 모르는 채 뒤집으면 안 된다.
    } finally {
      setPending(false);
    }
  }, [userId, productId, wished, pending, onChange, showToast]);

  return (
    <button
      type="button"
      onClick={toggle}
      disabled={pending}
      // 하트 모양만으로는 담긴 상태인지 읽어 주지 못한다. 스크린리더는 이 두 속성으로 판단한다.
      aria-pressed={wished}
      aria-label={wished ? '찜 빼기' : '찜하기'}
      title={wished ? '찜 빼기' : '찜하기'}
      className={`wishlist-heart ${wished ? 'is-wished' : ''} ${className}`.trim()}
    >
      <span aria-hidden="true">{wished ? '♥' : '♡'}</span>
    </button>
  );
};

export default WishlistHeart;
