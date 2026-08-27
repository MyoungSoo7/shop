import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { wishlistApi, type Wishlist, type WishlistItem } from '@/api/wishlist';
import { apiErrorMessage } from '@/lib/apiError';
import { useAuth } from '@/contexts/useAuth';

/**
 * 내 찜 목록.
 *
 * <p><b>없어진 상품을 목록에서 빼지 않는다.</b> 찜은 오래 담겨 있는 목록이라 그 사이 상품이 품절·
 * 단종·삭제된다. 조용히 빼면 사용자는 자기가 무엇을 담았는지 영영 알 수 없고, "분명 담아 놨는데"
 * 라는 문의가 남는다. 대신 <b>왜 살 수 없는지</b>를 그 자리에 쓴다.
 *
 * <p><b>일괄 정리는 무엇을 지울지 먼저 보여 준다.</b> 개수만 확인시키고 지우면, 되돌릴 수 없는
 * 동작에 대해 사용자가 확인한 것은 숫자뿐이다. 여기서는 대상 이름을 나열한 뒤에 묻고, 지운 다음에도
 * 서버가 돌려준 <b>지워진 목록</b>을 그대로 보여 준다.
 *
 * <p><b>품절은 정리 대상이 아니다.</b> 재입고를 기다리는 것이 찜의 가장 큰 용도다. 이 판정은 서버의
 * {@code gone} 을 그대로 쓴다 — 화면이 상태 문자열로 다시 판정하면 두 곳의 규칙이 갈라진다.
 */
export default function WishlistPage() {
  const { userId, loading: authLoading } = useAuth();

  const [wishlist, setWishlist] = useState<Wishlist | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [confirmingPurge, setConfirmingPurge] = useState(false);
  const [purged, setPurged] = useState<WishlistItem[] | null>(null);

  const load = useCallback(async () => {
    if (userId === null) return;
    setError(null);
    try {
      setWishlist(await wishlistApi.list(userId));
    } catch (err) {
      setError(apiErrorMessage(err, '찜 목록을 불러오지 못했습니다.'));
    }
  }, [userId]);

  useEffect(() => { void load(); }, [load]);

  const remove = async (productId: number) => {
    if (userId === null || busy) return;
    setBusy(true);
    try {
      await wishlistApi.remove(userId, productId);
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '찜에서 빼지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const purgeGone = async () => {
    if (userId === null || busy) return;
    setBusy(true);
    try {
      const result = await wishlistApi.purgeGone(userId);
      // 서버가 돌려준 결과 상태를 그대로 쓴다 — 다시 조회하면 그 사이 다른 탭의 변경이 섞인다.
      setWishlist(result.wishlist);
      setPurged(result.removed);
      setConfirmingPurge(false);
    } catch (err) {
      setError(apiErrorMessage(err, '정리하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  // authLoading 동안 "로그인이 필요합니다"를 띄우면, 새로고침 때마다 로그인한 사용자에게
  // 잠깐씩 로그아웃 화면이 번쩍인다.
  if (authLoading) {
    return <main className="mx-auto max-w-3xl p-6"><p>불러오는 중…</p></main>;
  }
  if (userId === null) {
    return (
      <main className="mx-auto max-w-3xl p-6">
        <p>찜 목록을 보려면 <Link to="/login" className="text-blue-600 underline">로그인</Link>이 필요합니다.</p>
      </main>
    );
  }

  const goneItems = wishlist?.items.filter(item => item.gone) ?? [];
  const nearLimit = wishlist !== null && wishlist.totalCount >= wishlist.maxItems * 0.9;

  return (
    <main className="mx-auto max-w-3xl p-6 space-y-6">
      <header>
        <h1 className="text-2xl font-bold">찜한 상품</h1>
        <p className="text-sm text-gray-500">
          품절·단종된 상품도 사유와 함께 그대로 둡니다. 재입고를 기다리는 것도 찜의 쓰임입니다.
        </p>
      </header>

      {error && <p role="alert" className="text-red-600">{error}</p>}

      {wishlist && (
        <p className="text-sm text-gray-600" data-testid="wishlist-count">
          {wishlist.totalCount}개 / 최대 {wishlist.maxItems}개
          {nearLimit && (
            <span className="ml-2 text-amber-700">
              보관 한도가 얼마 남지 않았습니다.
            </span>
          )}
        </p>
      )}

      {purged && (
        <section role="status" className="rounded border border-green-300 bg-green-50 p-3 text-sm">
          {purged.length === 0
            ? '정리할 항목이 없었습니다.'
            : `${purged.map(item => item.name).join(', ')} 를 정리했습니다.`}
        </section>
      )}

      {wishlist && wishlist.goneCount > 0 && (
        <section className="rounded border border-amber-300 bg-amber-50 p-4 space-y-2">
          <p className="text-sm">
            더 이상 판매되지 않는 상품이 <b>{wishlist.goneCount}개</b> 있습니다.
            품절 상품은 정리하지 않습니다.
          </p>

          {!confirmingPurge ? (
            <button type="button" onClick={() => setConfirmingPurge(true)} disabled={busy}
              className="rounded bg-amber-600 px-3 py-1.5 text-white disabled:opacity-50">
              정리하기
            </button>
          ) : (
            <div className="space-y-2" data-testid="purge-confirm">
              {/* 되돌릴 수 없는 동작이라, 확인 대상은 숫자가 아니라 이름이어야 한다. */}
              <p className="text-sm">아래 항목을 지웁니다. 되돌릴 수 없습니다.</p>
              <ul className="list-disc pl-5 text-sm">
                {goneItems.map(item => (
                  <li key={item.productId}>{item.name} — {item.reason}</li>
                ))}
              </ul>
              <div className="flex gap-2">
                <button type="button" onClick={() => void purgeGone()} disabled={busy}
                  className="rounded bg-red-600 px-3 py-1.5 text-white disabled:opacity-50">
                  {busy ? '정리 중…' : '지우기'}
                </button>
                <button type="button" onClick={() => setConfirmingPurge(false)} disabled={busy}
                  className="rounded border px-3 py-1.5">
                  취소
                </button>
              </div>
            </div>
          )}
        </section>
      )}

      {wishlist && wishlist.items.length === 0 && (
        <p className="text-gray-500" data-testid="wishlist-empty">
          아직 찜한 상품이 없습니다.
        </p>
      )}

      <ul className="space-y-3">
        {wishlist?.items.map(item => (
          <li key={item.productId}
            data-testid={`wishlist-item-${item.productId}`}
            className={`flex items-center gap-4 rounded border p-3 ${item.available ? '' : 'bg-gray-50'}`}>
            {item.primaryImageUrl ? (
              <img src={item.primaryImageUrl} alt="" className="h-16 w-16 rounded object-cover" />
            ) : (
              <div className="h-16 w-16 rounded bg-gray-200" aria-hidden="true" />
            )}

            <div className="min-w-0 flex-1">
              <p className="truncate font-medium">{item.name}</p>
              <p className="text-sm text-gray-600">
                {/* 삭제된 상품은 값을 매길 수 없다. 0원으로 그리면 공짜로 읽힌다. */}
                {item.price === null ? '가격 정보 없음' : `${item.price.toLocaleString()}원`}
              </p>
              {!item.available && (
                <p className="text-sm text-red-600" data-testid={`reason-${item.productId}`}>
                  {item.reason}
                </p>
              )}
            </div>

            <button type="button" onClick={() => void remove(item.productId)} disabled={busy}
              aria-label={`${item.name} 찜 빼기`}
              className="rounded border px-3 py-1.5 text-sm disabled:opacity-50">
              빼기
            </button>
          </li>
        ))}
      </ul>
    </main>
  );
}
