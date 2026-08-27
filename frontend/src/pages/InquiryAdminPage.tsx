import { useCallback, useEffect, useState } from 'react';
import { adminInquiryApi, type Inquiry } from '@/api/inquiry';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 문의 응대 콘솔 — {@code /admin/approvals/inquiries}.
 *
 * <p><b>대기열은 오래된 순이다.</b> 먼저 물어본 사람이 먼저 답을 받는다. 최신순으로 두면 오래
 * 기다린 문의가 목록 끝으로 밀려 영영 답을 못 받는다 — 실제로 레거시에서 그렇게 묻힌 문의가 있다.
 *
 * <p><b>대기 판정은 저장된 칼럼이 아니라 답변 유무다.</b> 레거시는 질문 행에 상태 칼럼을 두었는데
 * 답변 삭제 경로가 그것을 되돌리지 않아, 답변이 사라진 문의가 "답변 완료"인 채로 대기열에서
 * 빠져 있었다. 여기서는 답변을 지우는 즉시 그 문의가 대기열로 돌아온다.
 *
 * <p><b>답변 삭제는 어느 문의의 답변인지까지 대조한다.</b> 레거시는 답변 번호 하나만 보고 지워서
 * 다른 문의의 답변이 사라졌다. 서버가 짝이 맞지 않으면 404 를 준다.
 *
 * <p>인가는 화면이 아니라 SecurityConfig 의 {@code /admin/inquiries/**} 매처가 한다(ADMIN·MANAGER).
 * 이 저장소에는 {@code @EnableMethodSecurity} 가 없어 {@code @PreAuthorize} 는 조용히 무효다.
 */

const fmtDate = (s: string) =>
  new Date(s).toLocaleString('ko-KR', {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });

export default function InquiryAdminPage() {
  const [waiting, setWaiting] = useState<Inquiry[]>([]);
  const [selected, setSelected] = useState<Inquiry | null>(null);
  const [answer, setAnswer] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      setWaiting(await adminInquiryApi.listWaiting());
    } catch (err) {
      setError(apiErrorMessage(err, '대기열을 불러오지 못했습니다.'));
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const open = async (inquiryId: number) => {
    setError(null);
    try {
      // 목록의 행을 그대로 열지 않고 다시 읽는다 — 비밀글 본문은 이 경로로만 온다.
      setSelected(await adminInquiryApi.get(inquiryId));
      setAnswer('');
    } catch (err) {
      setError(apiErrorMessage(err, '문의를 불러오지 못했습니다.'));
    }
  };

  const submitAnswer = async () => {
    if (selected === null || busy) return;
    setBusy(true);
    setError(null);
    try {
      // 답변자를 보내지 않는다 — 토큰이 정한다.
      setSelected(await adminInquiryApi.answer(selected.id, answer));
      setAnswer('');
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '답변을 등록하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const deleteAnswer = async (answerId: number) => {
    if (selected === null || busy) return;
    setBusy(true);
    setError(null);
    try {
      // 서버가 돌려주는 것은 "지웠다"가 아니라 지운 뒤의 문의다. 상태도 함께 대기로 돌아온다.
      setSelected(await adminInquiryApi.deleteAnswer(selected.id, answerId));
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '답변을 지우지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <main className="mx-auto max-w-5xl p-6 space-y-6">
      <header>
        <h1 className="text-2xl font-bold">문의 응대</h1>
        <p className="text-sm text-gray-500">
          답변 대기 {waiting.length}건 · 오래 기다린 순서입니다.
        </p>
      </header>

      {error && <p role="alert" className="text-red-600">{error}</p>}

      <div className="grid gap-6 md:grid-cols-2">
        <section aria-label="답변 대기 목록" className="space-y-2">
          {waiting.length === 0 && (
            <p className="text-gray-500" data-testid="waiting-empty">답변을 기다리는 문의가 없습니다.</p>
          )}
          <ul className="space-y-2">
            {waiting.map((inquiry) => (
              <li key={inquiry.id}>
                <button type="button" onClick={() => void open(inquiry.id)}
                  data-testid={`waiting-${inquiry.id}`}
                  className={`w-full rounded border p-3 text-left text-sm hover:bg-gray-50 ${
                    selected?.id === inquiry.id ? 'border-blue-500 bg-blue-50' : ''
                  }`}>
                  <span className="flex items-center gap-2">
                    <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
                      {inquiry.typeLabel}
                    </span>
                    {inquiry.secret && <span aria-label="비밀글">🔒</span>}
                    <span className="font-medium">{inquiry.subject}</span>
                  </span>
                  <span className="mt-1 block text-xs text-gray-400">{fmtDate(inquiry.askedAt)}</span>
                </button>
              </li>
            ))}
          </ul>
        </section>

        <section aria-label="문의 상세" className="space-y-3">
          {selected === null ? (
            <p className="text-gray-500" data-testid="detail-empty">목록에서 문의를 고르세요.</p>
          ) : (
            <div className="rounded border p-4 space-y-3" data-testid="inquiry-detail">
              <div className="flex items-center gap-2">
                <span className="font-medium">{selected.subject}</span>
                <span className="ml-auto rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600"
                  data-testid="detail-status">
                  {selected.statusLabel}
                </span>
              </div>
              <p className="whitespace-pre-wrap text-sm text-gray-700">{selected.content}</p>

              {selected.answers.map((a) => (
                <div key={a.id} className="rounded bg-gray-50 p-3 text-sm" data-testid={`answer-${a.id}`}>
                  <p className="text-xs text-gray-500">답변 · {fmtDate(a.answeredAt)}</p>
                  <p className="whitespace-pre-wrap text-gray-700">{a.content}</p>
                  <button type="button" onClick={() => void deleteAnswer(a.id)} disabled={busy}
                    aria-label="답변 삭제"
                    className="mt-2 rounded border px-2 py-1 text-xs text-red-600 disabled:opacity-50">
                    삭제
                  </button>
                </div>
              ))}

              <div className="space-y-2">
                <textarea value={answer} onChange={(e) => setAnswer(e.target.value)} rows={4}
                  aria-label="답변 내용"
                  className="w-full rounded border px-2 py-1 text-sm" />
                <button type="button" onClick={() => void submitAnswer()} disabled={busy}
                  className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white disabled:opacity-50">
                  {busy ? '등록 중…' : '답변 등록'}
                </button>
              </div>
            </div>
          )}
        </section>
      </div>
    </main>
  );
}
