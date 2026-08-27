import React, { useCallback, useEffect, useState } from 'react';
import { inquiryApi, type Inquiry } from '@/api/inquiry';
import { apiErrorMessage } from '@/lib/apiError';
import { useAuth } from '@/contexts/useAuth';

/**
 * 상품 하나에 달린 문의 — 리뷰 블록 바로 아래.
 *
 * <p><b>왜 리뷰 옆인가.</b> 여기까지 사기 전에 묻고 싶은 사람이 갈 곳은 리뷰뿐이었는데, 리뷰는
 * <b>산 사람이</b> 쓰는 것이라 그 사람은 쓸 수 없다. 그래서 실제로 쓰이던 우회책은 고객센터 전화와
 * 남의 리뷰에 다는 대댓글이었고, 둘 다 답이 상품 페이지에 남지 않아 같은 질문이 계속 반복됐다.
 *
 * <p><b>못 읽는 문의도 줄은 남긴다.</b> 목록에서 빼 버리면 문의 개수가 보는 사람마다 달라지고,
 * 비밀글을 쓴 본인조차 자기 질문이 등록됐는지 확인할 수 없다. 가림은 서버가 이미 해서 보낸다
 * ({@code readable:false} + 가려진 제목·본문) — 화면이 다시 판정하면 두 곳의 규칙이 갈라진다.
 *
 * <p><b>답변이 달린 문의는 고칠 수 없다.</b> 답을 받은 뒤 질문을 바꾸면 서로 맞지 않는 한 쌍이
 * 남는다. 서버가 409 로 막고, 화면은 애초에 버튼을 내린다.
 */
interface ProductInquiriesProps {
  productId: number;
}

const ProductInquiries: React.FC<ProductInquiriesProps> = ({ productId }) => {
  const { userId } = useAuth();

  const [inquiries, setInquiries] = useState<Inquiry[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [asking, setAsking] = useState(false);
  const [subject, setSubject] = useState('');
  const [content, setContent] = useState('');
  const [secret, setSecret] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setInquiries(await inquiryApi.listForProduct(productId));
    } catch (err) {
      setError(apiErrorMessage(err, '문의를 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [productId]);

  useEffect(() => { void load(); }, [load]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      // 작성자를 보내지 않는다 — 토큰이 정한다.
      await inquiryApi.ask({ type: 'PRODUCT', productId, subject, content, secret });
      setSubject('');
      setContent('');
      setSecret(false);
      setAsking(false);
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '문의를 등록하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const withdraw = async (inquiryId: number) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await inquiryApi.withdraw(inquiryId);
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '문의를 철회하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="border border-gray-200 rounded-lg overflow-hidden" data-testid="product-inquiries">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        className="w-full flex items-center justify-between px-4 py-3 bg-gray-50 hover:bg-gray-100 transition-colors text-sm"
      >
        <span className="font-medium text-gray-700">
          {loading ? '문의 불러오는 중...' : `상품 문의 (${inquiries.length}개)`}
        </span>
        <svg
          className={`w-4 h-4 text-gray-400 transition-transform ${open ? 'rotate-180' : ''}`}
          fill="none" stroke="currentColor" viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth="2" d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {open && (
        <div className="px-4 py-4 space-y-3">
          {error && <p role="alert" className="text-sm text-red-600">{error}</p>}

          {!asking ? (
            <button type="button" onClick={() => setAsking(true)}
              className="rounded border px-3 py-1.5 text-sm">
              문의하기
            </button>
          ) : (
            <form onSubmit={(e) => void submit(e)} className="space-y-2" data-testid="inquiry-form">
              <label className="block text-sm">
                <span className="text-gray-600">제목</span>
                <input value={subject} onChange={(e) => setSubject(e.target.value)}
                  className="mt-1 w-full rounded border px-2 py-1" />
              </label>
              <label className="block text-sm">
                <span className="text-gray-600">내용</span>
                <textarea value={content} onChange={(e) => setContent(e.target.value)} rows={3}
                  className="mt-1 w-full rounded border px-2 py-1" />
              </label>
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={secret} onChange={(e) => setSecret(e.target.checked)} />
                <span>비밀글 — 나와 판매자만 봅니다</span>
              </label>
              <div className="flex gap-2">
                <button type="submit" disabled={busy}
                  className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white disabled:opacity-50">
                  {busy ? '등록 중…' : '등록'}
                </button>
                <button type="button" onClick={() => setAsking(false)} disabled={busy}
                  className="rounded border px-3 py-1.5 text-sm">
                  취소
                </button>
              </div>
            </form>
          )}

          {inquiries.length === 0 && !loading && (
            <p className="text-sm text-gray-500" data-testid="inquiries-empty">
              아직 등록된 문의가 없습니다.
            </p>
          )}

          <ul className="space-y-3">
            {inquiries.map((inquiry) => (
              <li key={inquiry.id} data-testid={`inquiry-${inquiry.id}`}
                className="rounded border border-gray-200 p-3 text-sm">
                <div className="flex items-center gap-2">
                  {inquiry.secret && <span aria-label="비밀글">🔒</span>}
                  <span className="font-medium">{inquiry.subject}</span>
                  <span className={`ml-auto rounded px-2 py-0.5 text-xs ${
                    inquiry.status === 'ANSWERED'
                      ? 'bg-emerald-100 text-emerald-800'
                      : 'bg-gray-100 text-gray-600'
                  }`}>
                    {inquiry.statusLabel}
                  </span>
                </div>

                <p className="mt-1 whitespace-pre-wrap text-gray-700">{inquiry.content}</p>

                {inquiry.answers.map((answer) => (
                  <div key={answer.id} className="mt-2 rounded bg-gray-50 p-2"
                    data-testid={`answer-${answer.id}`}>
                    <p className="text-xs text-gray-500">판매자 답변</p>
                    <p className="whitespace-pre-wrap text-gray-700">{answer.content}</p>
                  </div>
                ))}

                {/* 철회는 본인 것이고 답변 전일 때만. 서버도 같은 규칙을 강제한다. */}
                {inquiry.userId === userId && inquiry.status === 'WAITING' && (
                  <button type="button" onClick={() => void withdraw(inquiry.id)} disabled={busy}
                    className="mt-2 rounded border px-2 py-1 text-xs disabled:opacity-50">
                    철회
                  </button>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
};

export default ProductInquiries;
