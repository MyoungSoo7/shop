import { useCallback, useEffect, useState, type FormEvent } from 'react';
import {
  inquiryApi,
  type Inquiry,
  type InquiryTypeValue,
} from '@/api/inquiry';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 내 문의 — {@code /my/inquiries}.
 *
 * <p>화면 URL 이 {@code /inquiries} 가 아닌 이유: 그건 이 화면이 부르는 API 경로이고, nginx 두 벌이
 * {@code inquiries} 세그먼트를 게이트웨이로 프록시한다. 같은 URL 을 화면에 쓰면 새로고침에서
 * 목록 JSON 이 그대로 브라우저에 렌더된다. {@code /my/balances} 가 같은 이유로 그 자리에 있다.
 *
 * <p><b>사용자 식별자를 보내지 않는다.</b> 목록도 등록도 서버가 토큰에서 주체를 꺼낸다. 레거시는
 * {@code USERID} 를 폼으로 받아 그대로 저장해서, 남의 아이디를 적으면 남의 이름으로 문의가
 * 등록되고 그 뒤로는 그 사람만 볼 수 있었다.
 *
 * <p><b>상태는 저장된 값이 아니라 답변 유무에서 계산된다.</b> 그래서 답변이 지워지면 같은 순간
 * 목록도 "답변 대기"로 돌아온다 — 레거시에서 목록과 상세가 어긋나던 지점이다. 화면은 서버가 준
 * {@code status} 를 그대로 믿고, 수정·철회 버튼도 그 값으로만 판정한다(서버는 409 로 막는다).
 */

const TYPE_TABS: readonly { key: string; label: string; type?: InquiryTypeValue }[] = [
  { key: 'all', label: '전체' },
  { key: 'product', label: '상품 문의', type: 'PRODUCT' },
  { key: 'order', label: '주문 문의', type: 'ORDER' },
  { key: 'general', label: '1:1 문의', type: 'GENERAL' },
];

const fmtDate = (s: string) =>
  new Date(s).toLocaleString('ko-KR', {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  });

export default function InquiryPage() {
  const [tab, setTab] = useState('all');
  const [inquiries, setInquiries] = useState<Inquiry[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const [asking, setAsking] = useState(false);
  const [askType, setAskType] = useState<InquiryTypeValue>('GENERAL');
  const [askOrderId, setAskOrderId] = useState('');
  const [subject, setSubject] = useState('');
  const [content, setContent] = useState('');
  const [secret, setSecret] = useState(false);

  const [editingId, setEditingId] = useState<number | null>(null);
  const [editSubject, setEditSubject] = useState('');
  const [editContent, setEditContent] = useState('');
  const [editSecret, setEditSecret] = useState(false);

  const load = useCallback(async () => {
    setError(null);
    try {
      const type = TYPE_TABS.find((t) => t.key === tab)?.type;
      setInquiries(await inquiryApi.listMine(type));
    } catch (err) {
      setError(apiErrorMessage(err, '문의를 불러오지 못했습니다.'));
    }
  }, [tab]);

  useEffect(() => { void load(); }, [load]);

  const ask = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await inquiryApi.ask({
        type: askType,
        // 주문 문의만 주문 번호를 함께 보낸다. 서버 도메인이 종류별로 무엇이 필요한지 강제한다.
        orderId: askType === 'ORDER' && askOrderId !== '' ? Number(askOrderId) : null,
        subject,
        content,
        secret,
      });
      setSubject('');
      setContent('');
      setAskOrderId('');
      setSecret(false);
      setAsking(false);
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '문의를 등록하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const startEdit = (inquiry: Inquiry) => {
    setEditingId(inquiry.id);
    setEditSubject(inquiry.subject);
    setEditContent(inquiry.content);
    setEditSecret(inquiry.secret);
  };

  const saveEdit = async (inquiryId: number) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await inquiryApi.edit(inquiryId, {
        subject: editSubject,
        content: editContent,
        secret: editSecret,
      });
      setEditingId(null);
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '문의를 수정하지 못했습니다.'));
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
    <main className="mx-auto max-w-3xl p-6 space-y-6">
      <header>
        <h1 className="text-2xl font-bold">내 문의</h1>
        <p className="text-sm text-gray-500">
          답변이 달리기 전까지는 고치거나 철회할 수 있습니다.
        </p>
      </header>

      {error && <p role="alert" className="text-red-600">{error}</p>}

      <nav className="flex gap-2" aria-label="문의 종류">
        {TYPE_TABS.map((t) => (
          <button key={t.key} type="button" onClick={() => setTab(t.key)}
            aria-pressed={tab === t.key}
            className={`rounded px-3 py-1.5 text-sm ${
              tab === t.key ? 'bg-blue-600 text-white' : 'border'
            }`}>
            {t.label}
          </button>
        ))}
      </nav>

      {!asking ? (
        <button type="button" onClick={() => setAsking(true)}
          className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white">
          문의하기
        </button>
      ) : (
        <form onSubmit={(e) => void ask(e)} className="space-y-2 rounded border p-4"
          data-testid="ask-form">
          <label className="block text-sm">
            <span className="text-gray-600">종류</span>
            <select value={askType} onChange={(e) => setAskType(e.target.value as InquiryTypeValue)}
              className="mt-1 w-full rounded border px-2 py-1">
              <option value="GENERAL">1:1 문의</option>
              <option value="ORDER">주문 문의</option>
            </select>
          </label>

          {/* 상품 문의는 여기서 만들지 않는다 — 어느 상품인지는 상품 화면에서만 분명하다. */}
          {askType === 'ORDER' && (
            <label className="block text-sm">
              <span className="text-gray-600">주문 번호</span>
              <input value={askOrderId} onChange={(e) => setAskOrderId(e.target.value)}
                inputMode="numeric"
                className="mt-1 w-full rounded border px-2 py-1" />
            </label>
          )}

          <label className="block text-sm">
            <span className="text-gray-600">제목</span>
            <input value={subject} onChange={(e) => setSubject(e.target.value)}
              className="mt-1 w-full rounded border px-2 py-1" />
          </label>
          <label className="block text-sm">
            <span className="text-gray-600">내용</span>
            <textarea value={content} onChange={(e) => setContent(e.target.value)} rows={4}
              className="mt-1 w-full rounded border px-2 py-1" />
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={secret} onChange={(e) => setSecret(e.target.checked)} />
            <span>비밀글</span>
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

      {inquiries.length === 0 && (
        <p className="text-gray-500" data-testid="inquiries-empty">등록한 문의가 없습니다.</p>
      )}

      <ul className="space-y-3">
        {inquiries.map((inquiry) => (
          <li key={inquiry.id} data-testid={`inquiry-${inquiry.id}`}
            className="rounded border p-4 space-y-2">
            <div className="flex items-center gap-2">
              <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
                {inquiry.typeLabel}
              </span>
              {inquiry.secret && <span aria-label="비밀글">🔒</span>}
              <span className="font-medium">{inquiry.subject}</span>
              <span className={`ml-auto rounded px-2 py-0.5 text-xs ${
                inquiry.status === 'ANSWERED'
                  ? 'bg-emerald-100 text-emerald-800'
                  : 'bg-gray-100 text-gray-600'
              }`} data-testid={`status-${inquiry.id}`}>
                {inquiry.statusLabel}
              </span>
            </div>

            <p className="text-xs text-gray-400">{fmtDate(inquiry.askedAt)}</p>

            {editingId === inquiry.id ? (
              <div className="space-y-2" data-testid={`edit-form-${inquiry.id}`}>
                <input value={editSubject} onChange={(e) => setEditSubject(e.target.value)}
                  aria-label="제목 수정"
                  className="w-full rounded border px-2 py-1 text-sm" />
                <textarea value={editContent} onChange={(e) => setEditContent(e.target.value)}
                  aria-label="내용 수정" rows={3}
                  className="w-full rounded border px-2 py-1 text-sm" />
                <label className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked={editSecret}
                    onChange={(e) => setEditSecret(e.target.checked)} />
                  <span>비밀글</span>
                </label>
                <div className="flex gap-2">
                  <button type="button" onClick={() => void saveEdit(inquiry.id)} disabled={busy}
                    className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white disabled:opacity-50">
                    저장
                  </button>
                  <button type="button" onClick={() => setEditingId(null)} disabled={busy}
                    className="rounded border px-3 py-1.5 text-sm">
                    취소
                  </button>
                </div>
              </div>
            ) : (
              <p className="whitespace-pre-wrap text-sm text-gray-700">{inquiry.content}</p>
            )}

            {inquiry.answers.map((answer) => (
              <div key={answer.id} className="rounded bg-gray-50 p-3 text-sm"
                data-testid={`answer-${answer.id}`}>
                <p className="text-xs text-gray-500">답변 · {fmtDate(answer.answeredAt)}</p>
                <p className="whitespace-pre-wrap text-gray-700">{answer.content}</p>
              </div>
            ))}

            {/* 답변이 달리면 수정·철회 버튼 자체를 내린다. 서버도 409 로 막지만, 누를 수 있는
                버튼을 두고 눌렀을 때 실패시키는 것은 화면이 규칙을 모르는 것과 같다. */}
            {inquiry.status === 'WAITING' && editingId !== inquiry.id && (
              <div className="flex gap-2">
                <button type="button" onClick={() => startEdit(inquiry)} disabled={busy}
                  className="rounded border px-3 py-1.5 text-sm disabled:opacity-50">
                  수정
                </button>
                <button type="button" onClick={() => void withdraw(inquiry.id)} disabled={busy}
                  className="rounded border px-3 py-1.5 text-sm text-red-600 disabled:opacity-50">
                  철회
                </button>
              </div>
            )}
          </li>
        ))}
      </ul>
    </main>
  );
}
