import { useCallback, useEffect, useState } from 'react';
import {
  reviewAdminApi,
  type ReviewPage,
  type ReviewQuery,
  type ReviewRow,
  type ReviewStatusCount,
} from '@/api/reviewAdmin';
import { saveBlob } from '@/api/auditLog';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 리뷰 관리 콘솔.
 *
 * <p><b>왜 필요한가</b>: 지금까지 리뷰를 내리는 유일한 방법은 작성자 본인의 삭제였다. 욕설·
 * 개인정보 노출·경쟁사 도배가 올라와도 운영자가 할 수 있는 일은 DB 를 직접 손대는 것뿐이었다.
 *
 * <p><b>"삭제"라고 쓰지 않는다</b>: 이 화면의 조작은 블라인드이고 원문은 남는다. 문구가
 * 삭제라고 말하면 운영자는 되돌릴 수 없다고 믿어 주저하거나, 반대로 지워졌다고 오해한다.
 *
 * <p><b>평점 상한이 기본 필터인 이유</b>: 운영자가 훑는 것은 늘 낮은 평점이다. "2점 이하"를
 * 먼저 보여 주는 편이 실제 동선에 맞고, 필요하면 전체로 넓히면 된다.
 */

const PAGE_SIZE = 50;

const STATUS_LABEL: Record<string, string> = {
  VISIBLE: '공개',
  HIDDEN: '블라인드',
};

export default function ReviewAdminPage() {
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [maxRating, setMaxRating] = useState('');
  const [productId, setProductId] = useState('');
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [page, setPage] = useState(0);

  const [statuses, setStatuses] = useState<string[]>([]);
  const [result, setResult] = useState<ReviewPage | null>(null);
  const [counts, setCounts] = useState<ReviewStatusCount[]>([]);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);

  const query = useCallback(
    (withPaging: boolean): ReviewQuery => ({
      keyword: keyword.trim() || undefined,
      status: status || undefined,
      maxRating: maxRating ? Number(maxRating) : undefined,
      productId: productId ? Number(productId) : undefined,
      from: from || undefined,
      to: to || undefined,
      ...(withPaging ? { page, size: PAGE_SIZE } : {}),
    }),
    [keyword, status, maxRating, productId, from, to, page],
  );

  useEffect(() => {
    let cancelled = false;
    void reviewAdminApi
      .statuses()
      .then(list => {
        if (!cancelled) setStatuses(list);
      })
      .catch(() => {
        if (!cancelled) setStatuses([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const load = useCallback(async () => {
    setError(null);
    setLoading(true);
    try {
      const [pageResult, statusCounts] = await Promise.all([
        reviewAdminApi.search(query(true)),
        // 상태별 집계에 상태 필터를 실으면 고른 상태 하나만 남아 집계가 무의미해진다.
        reviewAdminApi.statusCounts({ ...query(false), status: undefined }),
      ]);
      setResult(pageResult);
      setCounts(statusCounts);
    } catch (err) {
      setError(apiErrorMessage(err, '리뷰 목록을 불러오지 못했습니다.'));
      setResult(null);
      setCounts([]);
    } finally {
      setLoading(false);
    }
  }, [query]);

  useEffect(() => {
    void load();
  }, [load]);

  const search = () => {
    if (page === 0) {
      void load();
    } else {
      setPage(0);
    }
  };

  const runAction = async (reviewId: number, action: () => Promise<unknown>, message: string) => {
    setError(null);
    setNotice(null);
    setBusyId(reviewId);
    try {
      await action();
      setNotice(message);
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '처리에 실패했습니다.'));
    } finally {
      setBusyId(null);
    }
  };

  const hide = (review: ReviewRow) => {
    const reason = window.prompt(
      '블라인드 사유를 입력하세요. 원문은 보존되며 공개 목록에서만 빠집니다.');
    if (!reason?.trim()) return;
    void runAction(review.id, () => reviewAdminApi.hide(review.id, reason.trim()),
      `리뷰 #${review.id} 를 블라인드했습니다. 원문은 남아 있습니다.`);
  };

  const restore = (review: ReviewRow) =>
    runAction(review.id, () => reviewAdminApi.restore(review.id),
      `리뷰 #${review.id} 를 다시 공개했습니다.`);

  const download = async () => {
    setNotice(null);
    setError(null);
    try {
      const exported = await reviewAdminApi.export(query(false));
      saveBlob(exported.blob, exported.fileName);
      setNotice(
        exported.truncated
          ? `내려받은 CSV 는 조건에 맞는 ${exported.total.toLocaleString()}건 중 앞 5,000건만 담고 있습니다. 조건을 좁혀 다시 받으세요.`
          : `${exported.total.toLocaleString()}건을 모두 내려받았습니다.`,
      );
    } catch (err) {
      setError(apiErrorMessage(err, 'CSV 내려받기에 실패했습니다.'));
    }
  };

  const totalPages = result?.totalPages ?? 0;

  return (
    <main className="space-y-6 p-6">
      <header>
        <h1 className="text-2xl font-bold">리뷰 관리</h1>
        <p className="text-sm text-gray-500">
          신고·부적절 리뷰를 블라인드합니다. 블라인드는 삭제가 아니라 노출 차단이며 원문은 보존됩니다.
        </p>
      </header>

      <section className="grid gap-3 rounded border p-4 sm:grid-cols-3">
        <label className="flex flex-col gap-1 sm:col-span-2">
          <span className="text-sm">본문 검색</span>
          <input aria-label="본문 검색" value={keyword} onChange={e => setKeyword(e.target.value)}
            placeholder="리뷰 내용 일부" className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">상품 ID</span>
          <input aria-label="상품 ID" value={productId} onChange={e => setProductId(e.target.value)}
            inputMode="numeric" className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">평점 상한 (이하)</span>
          <select aria-label="평점 상한" value={maxRating} onChange={e => setMaxRating(e.target.value)}
            className="rounded border px-3 py-2">
            <option value="">전체</option>
            {[1, 2, 3, 4, 5].map(n => <option key={n} value={n}>{n}점 이하</option>)}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">노출 상태</span>
          <select aria-label="노출 상태" value={status} onChange={e => setStatus(e.target.value)}
            className="rounded border px-3 py-2">
            <option value="">전체</option>
            {statuses.map(name => (
              <option key={name} value={name}>{STATUS_LABEL[name] ?? name}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">작성 시작일</span>
          <input aria-label="작성 시작일" type="date" value={from} onChange={e => setFrom(e.target.value)}
            className="rounded border px-3 py-2" />
        </label>
        <label className="flex flex-col gap-1">
          <span className="text-sm">작성 종료일</span>
          <input aria-label="작성 종료일" type="date" value={to} onChange={e => setTo(e.target.value)}
            className="rounded border px-3 py-2" />
        </label>

        <div className="flex items-end gap-2 sm:col-span-3">
          <button type="button" onClick={search} disabled={loading}
            className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
            {loading ? '조회 중…' : '조회'}
          </button>
          <button type="button" onClick={() => void download()} disabled={loading}
            className="rounded bg-slate-700 px-4 py-2 text-white disabled:opacity-50">
            CSV 내려받기
          </button>
        </div>
      </section>

      {error && <p role="alert" className="text-red-600">{error}</p>}
      {notice && <p role="status" className="rounded bg-amber-50 p-3 text-amber-800">{notice}</p>}

      {counts.length > 0 && (
        <section aria-label="노출 상태별 건수" className="flex flex-wrap gap-2">
          {counts.map(item => (
            <button key={item.status} type="button"
              onClick={() => { setStatus(item.status); setPage(0); }}
              className="rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-700 hover:bg-gray-200">
              {STATUS_LABEL[item.status] ?? item.status} <b>{item.count.toLocaleString()}</b>
            </button>
          ))}
        </section>
      )}

      <section>
        <p className="mb-2 text-sm text-gray-600">
          총 {(result?.totalElements ?? 0).toLocaleString()}건
        </p>

        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="bg-gray-50">
              <tr>
                <th className="px-3 py-2">작성일</th>
                <th className="px-3 py-2">상품</th>
                <th className="px-3 py-2">작성자</th>
                <th className="px-3 py-2">평점</th>
                <th className="px-3 py-2">내용</th>
                <th className="px-3 py-2">상태</th>
                <th className="px-3 py-2">조작</th>
              </tr>
            </thead>
            <tbody>
              {(result?.content ?? []).map(review => (
                <tr key={review.id} className="border-t align-top">
                  <td className="whitespace-nowrap px-3 py-2">{review.createdAt.slice(0, 10)}</td>
                  <td className="px-3 py-2">{review.productName ?? `#${review.productId}`}</td>
                  <td className="px-3 py-2">{review.userEmail ?? `#${review.userId}`}</td>
                  <td className="px-3 py-2">{'★'.repeat(review.rating)}</td>
                  <td className="max-w-md px-3 py-2">
                    <p className="whitespace-pre-wrap">{review.content}</p>
                    {review.hiddenReason && (
                      <p className="mt-1 text-xs text-red-700">사유: {review.hiddenReason}</p>
                    )}
                  </td>
                  <td className="px-3 py-2">{STATUS_LABEL[review.status] ?? review.status}</td>
                  <td className="px-3 py-2">
                    {review.status === 'HIDDEN' ? (
                      <button type="button" disabled={busyId === review.id}
                        onClick={() => void restore(review)}
                        className="rounded bg-blue-600 px-2 py-1 text-xs text-white disabled:opacity-50">
                        공개로 되돌리기
                      </button>
                    ) : (
                      <button type="button" disabled={busyId === review.id}
                        onClick={() => hide(review)}
                        className="rounded bg-red-600 px-2 py-1 text-xs text-white disabled:opacity-50">
                        블라인드
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {!loading && (result?.content.length ?? 0) === 0 && (
          <p className="py-6 text-center text-gray-500">조건에 맞는 리뷰가 없습니다.</p>
        )}
      </section>

      {totalPages > 1 && (
        <nav aria-label="페이지" className="flex items-center gap-3">
          <button type="button" onClick={() => setPage(p => Math.max(0, p - 1))}
            disabled={page === 0 || loading} className="rounded border px-3 py-1 disabled:opacity-40">
            이전
          </button>
          <span className="text-sm">{page + 1} / {totalPages}</span>
          <button type="button" onClick={() => setPage(p => Math.min(totalPages - 1, p + 1))}
            disabled={page >= totalPages - 1 || loading}
            className="rounded border px-3 py-1 disabled:opacity-40">
            다음
          </button>
        </nav>
      )}
    </main>
  );
}
