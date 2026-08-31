import { useCallback, useEffect, useState } from 'react';
import { formatMoney, sellerApi, type Submission, type SubmissionPage } from '@/api/seller';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 상품 심사 — 운영자가 셀러 신청서를 승인/반려하는 큐.
 *
 * <p><b>왜 셀러 콘솔 안이 아니라 시스템 관리 아래인가.</b> 이 화면의 대상은 "내 조직" 이 아니라
 * 전체 신청서다. 셀러 콘솔 그룹에 넣으면 그 그룹의 권한이 USER+ADMIN 이 되어야 하는데, 메뉴
 * 권한은 정확 일치라서 운영자에게 자기가 403 을 받는 링크(/seller/products)가 함께 보이게 된다.
 * 다른 서비스의 운영자 화면(환불 운영·셀러 등급)도 같은 이유로 여기 있다.
 *
 * <p><b>반려에는 사유가 필수다.</b> 서버도 빈 사유를 400 으로 막지만, 사유 없는 반려는 셀러가
 * 무엇을 고쳐야 하는지 알 수 없어 같은 신청서가 그대로 다시 올라온다 — 큐가 줄지 않는다.
 *
 * <p><b>승인 결과를 "등록 완료" 로 쓰지 않는다.</b> 승인은 카탈로그 등록 요청을 보낸 것이고,
 * 상품은 order-service 가 이벤트를 받아 만든다. 운영자 화면이 이 시차를 감추면, 등록이 실패한
 * 신청서를 아무도 다시 보지 않는다.
 */

const PAGE_SIZE = 20;

export default function ProductSubmissionReviewPage() {
  const [result, setResult] = useState<SubmissionPage | null>(null);
  const [page, setPage] = useState(0);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rejecting, setRejecting] = useState<Submission | null>(null);
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async (nextPage: number) => {
    setLoading(true);
    setError(null);
    try {
      setResult(await sellerApi.pendingSubmissions(nextPage, PAGE_SIZE));
      setPage(nextPage);
    } catch (err) {
      setError(apiErrorMessage(err, '심사 대기 목록을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(0); }, [load]);

  const approve = async (submission: Submission) => {
    setBusyId(submission.submissionId);
    setError(null);
    setNotice(null);
    try {
      await sellerApi.approveSubmission(submission.submissionId);
      setNotice(`신청서 ${submission.submissionId} 승인. 카탈로그 등록은 잠시 뒤 반영됩니다.`);
      await load(page);
    } catch (err) {
      setError(apiErrorMessage(err, '승인하지 못했습니다.'));
    } finally {
      setBusyId(null);
    }
  };

  const reject = async () => {
    if (rejecting === null || reason.trim() === '') return;
    setBusyId(rejecting.submissionId);
    setError(null);
    setNotice(null);
    try {
      await sellerApi.rejectSubmission(rejecting.submissionId, reason.trim());
      setNotice(`신청서 ${rejecting.submissionId} 반려. 셀러가 수정 후 다시 올릴 수 있습니다.`);
      setRejecting(null);
      setReason('');
      await load(page);
    } catch (err) {
      setError(apiErrorMessage(err, '반려하지 못했습니다.'));
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-4">
      <header>
        <h1 className="text-xl font-semibold text-gray-900">상품 심사</h1>
        <p className="text-sm text-gray-600">
          셀러가 올린 상품 등록 신청서입니다. 승인하면 몰 카탈로그에 등록 요청이 나갑니다.
        </p>
      </header>

      {notice !== null && (
        <p className="rounded bg-blue-50 p-3 text-sm text-blue-800" data-testid="review-notice">{notice}</p>
      )}
      {error !== null && (
        <p className="rounded bg-red-50 p-3 text-sm text-red-700" data-testid="review-error">{error}</p>
      )}

      {loading
        ? <p className="text-sm text-gray-500" data-testid="review-loading">불러오는 중…</p>
        : result === null || result.content.length === 0
          ? <p className="text-sm text-gray-500" data-testid="review-empty">심사 대기 중인 신청서가 없습니다.</p>
          : (
            <>
              <div className="overflow-x-auto rounded-lg bg-white shadow">
                <table className="min-w-full text-sm" data-testid="review-table">
                  <thead className="bg-gray-50 text-left text-gray-600">
                    <tr>
                      <th className="px-3 py-2">번호</th>
                      <th className="px-3 py-2">셀러</th>
                      <th className="px-3 py-2">구분</th>
                      <th className="px-3 py-2">상품명</th>
                      <th className="px-3 py-2 text-right">판매가</th>
                      <th className="px-3 py-2 text-right">재고</th>
                      <th className="px-3 py-2">제출일시</th>
                      <th className="px-3 py-2">처리</th>
                    </tr>
                  </thead>
                  <tbody>
                    {result.content.map((submission) => (
                      <tr key={submission.submissionId} className="border-t border-gray-100">
                        <td className="px-3 py-2">{submission.submissionId}</td>
                        <td className="px-3 py-2">{submission.sellerId}</td>
                        <td className="px-3 py-2">
                          {submission.type === 'UPDATE'
                            ? `수정 (상품 ${submission.baseProductId})`
                            : '신규'}
                        </td>
                        <td className="px-3 py-2">
                          <div>{submission.name}</div>
                          {submission.description !== null && (
                            <div className="text-xs text-gray-500">{submission.description}</div>
                          )}
                        </td>
                        <td className="px-3 py-2 text-right">{formatMoney(submission.price)}</td>
                        <td className="px-3 py-2 text-right">{submission.stock.toLocaleString('ko-KR')}</td>
                        <td className="px-3 py-2">
                          {submission.submittedAt === null
                            ? '—'
                            : new Date(submission.submittedAt).toLocaleString('ko-KR')}
                        </td>
                        <td className="space-x-2 px-3 py-2">
                          <button type="button" disabled={busyId === submission.submissionId}
                                  onClick={() => void approve(submission)}
                                  data-testid={`approve-${submission.submissionId}`}
                                  className="rounded bg-green-600 px-2 py-1 text-xs text-white disabled:opacity-40">
                            승인
                          </button>
                          <button type="button" disabled={busyId === submission.submissionId}
                                  onClick={() => { setRejecting(submission); setReason(''); }}
                                  data-testid={`reject-${submission.submissionId}`}
                                  className="rounded border border-red-300 px-2 py-1 text-xs text-red-700 disabled:opacity-40">
                            반려
                          </button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              <div className="flex items-center justify-between text-sm text-gray-600">
                <span data-testid="review-total">
                  대기 {result.totalElements.toLocaleString('ko-KR')}건 · {page + 1}/{Math.max(result.totalPages, 1)}쪽
                </span>
                <span className="space-x-2">
                  <button type="button" disabled={page === 0} data-testid="review-prev"
                          onClick={() => void load(page - 1)}
                          className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40">이전</button>
                  <button type="button" disabled={page + 1 >= result.totalPages} data-testid="review-next"
                          onClick={() => void load(page + 1)}
                          className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40">다음</button>
                </span>
              </div>
            </>
          )}

      {rejecting !== null && (
        <section className="rounded-lg bg-white p-4 shadow" data-testid="reject-form">
          <h2 className="font-semibold text-gray-900">신청서 {rejecting.submissionId} 반려</h2>
          <p className="mt-1 text-xs text-gray-500">
            사유는 셀러에게 그대로 보입니다. 무엇을 고쳐야 하는지 적어 주세요.
          </p>
          <textarea rows={3} value={reason} data-testid="reject-reason"
                    className="mt-2 w-full rounded border border-gray-300 px-2 py-1 text-sm"
                    onChange={(e) => setReason(e.target.value)} />
          <div className="mt-2 space-x-2">
            <button type="button" onClick={() => void reject()}
                    disabled={reason.trim() === '' || busyId !== null}
                    data-testid="reject-confirm"
                    className="rounded bg-red-600 px-3 py-1.5 text-sm text-white disabled:opacity-40">
              반려 확정
            </button>
            <button type="button" onClick={() => { setRejecting(null); setReason(''); }}
                    data-testid="reject-cancel"
                    className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-700">
              취소
            </button>
          </div>
        </section>
      )}
    </div>
  );
}
