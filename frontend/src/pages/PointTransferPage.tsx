import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { pointApi } from '@/api/point';
import {
  newRequestId,
  pointTransferApi,
  type TransferHistoryEntry,
  type TransferResult,
} from '@/api/pointTransfer';
import { apiErrorMessage } from '@/lib/apiError';
import { useAuth } from '@/contexts/useAuth';

/**
 * 포인트 선물 — 내 포인트를 다른 회원에게 보낸다.
 *
 * <p><b>되돌리는 버튼은 없다.</b> 받은 사람이 이미 썼을 수 있어 서버에도 취소 API 가 없다.
 * 그래서 이 화면은 보내기 전에 <b>확인 단계</b>를 한 번 둔다 — 주소록의 삭제 확인과 같은 이유다.
 * 확인 문구에는 받는 분과 금액을 다시 적는다. 버튼만 두 번 누르게 하면 두 번째 클릭은 첫 번째의
 * 연장이 되어 아무것도 막지 못한다.
 *
 * <p><b>실패해도 멱등 키를 바꾸지 않는다.</b> 응답을 못 받은 요청은 서버에서 이미 처리됐을 수
 * 있다. 재시도할 때 키를 새로 뽑으면 그게 곧 두 번 보내기다. 키는 <b>성공한 뒤에만</b> 새로 뽑는다.
 *
 * <p><b>이름 칸을 이메일 옆에 붙여 두고 설명을 단다.</b> 이 칸은 편의가 아니라 오타 방지다 —
 * 이메일 한 글자가 틀려도 실재하는 회원이면 포인트는 그 사람에게 간다. 서버는 둘 중 하나만
 * 어긋나도 <b>같은 문구</b>로 거절하므로, 화면이 "이메일은 맞는데 이름이 틀렸습니다" 같은 추측
 * 문구를 지어내면 안 된다. 그 구분이 곧 가입 여부 조회가 된다.
 */

interface FormState {
  recipientEmail: string;
  recipientName: string;
  amount: string;
  message: string;
}

const EMPTY_FORM: FormState = {
  recipientEmail: '',
  recipientName: '',
  amount: '',
  message: '',
};

const MAX_MESSAGE = 200;

const formatPoint = (value: number) => `${value.toLocaleString('ko-KR')}P`;

const formatMoment = (iso: string) => {
  const at = new Date(iso);
  return Number.isNaN(at.getTime()) ? iso : at.toLocaleString('ko-KR');
};

export default function PointTransferPage() {
  const { userId, loading: authLoading } = useAuth();

  const [balance, setBalance] = useState<number | null>(null);
  const [history, setHistory] = useState<TransferHistoryEntry[]>([]);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [requestId, setRequestId] = useState<string>(() => newRequestId());
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<TransferResult | null>(null);

  const load = useCallback(async () => {
    if (userId === null) return;
    setError(null);
    try {
      const [balanceResult, historyResult] = await Promise.all([
        pointApi.myBalance(),
        pointTransferApi.history(),
      ]);
      setBalance(balanceResult.available);
      setHistory(historyResult);
    } catch (err) {
      setError(apiErrorMessage(err, '포인트 정보를 불러오지 못했습니다.'));
    }
  }, [userId]);

  useEffect(() => { void load(); }, [load]);

  const amount = Number(form.amount);
  const amountValid = form.amount.trim() !== ''
    && Number.isInteger(amount)
    && amount > 0;
  const canSubmit = form.recipientEmail.trim() !== ''
    && form.recipientName.trim() !== ''
    && amountValid;

  const send = async () => {
    if (userId === null || busy || !canSubmit) return;
    setBusy(true);
    setError(null);
    try {
      const sent = await pointTransferApi.send({
        requestId,
        recipientEmail: form.recipientEmail.trim(),
        recipientName: form.recipientName.trim(),
        amount,
        message: form.message.trim() === '' ? null : form.message.trim(),
      });
      setResult(sent);
      setBalance(sent.remainingBalance);
      setForm(EMPTY_FORM);
      setConfirming(false);
      // 성공한 뒤에만 키를 새로 뽑는다. 실패한 요청은 서버에서 이미 처리됐을 수 있어
      // 같은 키로 다시 보내야 두 번 나가지 않는다.
      setRequestId(newRequestId());
      setHistory(await pointTransferApi.history());
    } catch (err) {
      setError(apiErrorMessage(err, '포인트를 보내지 못했습니다.'));
      setConfirming(false);
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
        <p>
          포인트를 선물하려면 <Link to="/login" className="text-blue-600 underline">로그인</Link>이 필요합니다.
        </p>
      </main>
    );
  }

  const notEnough = balance !== null && amountValid && amount > balance;

  return (
    <main className="mx-auto max-w-3xl p-6 space-y-6">
      <header>
        <h1 className="text-2xl font-bold">포인트 선물</h1>
        <p className="text-sm text-gray-500">
          내 포인트를 다른 회원에게 보냅니다. 보낸 뒤에는 되돌릴 수 없습니다.
        </p>
      </header>

      {error && <p role="alert" className="text-red-600">{error}</p>}

      <p className="text-sm text-gray-700" data-testid="transfer-balance">
        보낼 수 있는 포인트: {balance === null ? '—' : formatPoint(balance)}
      </p>

      {result && (
        <div role="status" data-testid="transfer-result"
          className="rounded border border-green-300 bg-green-50 p-3 text-sm">
          <p>
            {result.recipientName}({result.recipientEmail}) 님께 {formatPoint(result.amount)}을
            보냈습니다. (선물번호 {result.transferNo})
          </p>
          {/* 이미 처리된 요청이라는 사실을 숨기면, 사용자는 두 번 보냈다고 오해해 문의한다. */}
          {result.alreadyProcessed && (
            <p className="text-gray-600" data-testid="transfer-replayed">
              이미 처리된 요청이라 다시 보내지 않았습니다.
            </p>
          )}
        </div>
      )}

      <section className="rounded border p-4 space-y-3">
        <h2 className="font-semibold">받는 분</h2>

        <div className="grid gap-3 sm:grid-cols-2">
          <label className="block text-sm">
            <span className="text-gray-700">이메일</span>
            <input
              type="email"
              value={form.recipientEmail}
              onChange={e => { setForm({ ...form, recipientEmail: e.target.value }); setConfirming(false); }}
              data-testid="transfer-recipientEmail"
              className="mt-1 w-full rounded border px-2 py-1.5"
            />
          </label>
          <label className="block text-sm">
            <span className="text-gray-700">이름</span>
            <span className="ml-1 text-xs text-gray-500">이메일 오타로 남에게 가는 것을 막습니다</span>
            <input
              type="text"
              value={form.recipientName}
              onChange={e => { setForm({ ...form, recipientName: e.target.value }); setConfirming(false); }}
              data-testid="transfer-recipientName"
              className="mt-1 w-full rounded border px-2 py-1.5"
            />
          </label>
        </div>

        <label className="block text-sm">
          <span className="text-gray-700">보낼 포인트</span>
          <input
            type="number"
            min={1}
            step={1}
            value={form.amount}
            onChange={e => { setForm({ ...form, amount: e.target.value }); setConfirming(false); }}
            data-testid="transfer-amount"
            className="mt-1 w-full rounded border px-2 py-1.5"
          />
        </label>
        {form.amount.trim() !== '' && !amountValid && (
          <p className="text-sm text-amber-700" data-testid="transfer-amount-invalid">
            1P 이상의 정수만 보낼 수 있습니다.
          </p>
        )}
        {notEnough && (
          <p className="text-sm text-amber-700" data-testid="transfer-not-enough">
            보유 포인트({formatPoint(balance)})보다 많습니다.
          </p>
        )}

        <label className="block text-sm">
          <span className="text-gray-700">한마디</span>
          <span className="ml-1 text-xs text-gray-500">선택 · 최대 {MAX_MESSAGE}자</span>
          <textarea
            value={form.message}
            maxLength={MAX_MESSAGE}
            onChange={e => setForm({ ...form, message: e.target.value })}
            data-testid="transfer-message"
            className="mt-1 w-full rounded border px-2 py-1.5"
          />
        </label>

        {confirming ? (
          <div className="space-y-2 rounded border border-amber-300 bg-amber-50 p-3"
            data-testid="transfer-confirm">
            {/* 받는 분과 금액을 다시 적는다. 버튼만 두 번 누르게 하면 아무것도 막지 못한다. */}
            <p className="text-sm">
              {form.recipientName.trim()}({form.recipientEmail.trim()}) 님께
              {' '}{formatPoint(amount)}을 보냅니다. 되돌릴 수 없습니다.
            </p>
            <div className="flex gap-2">
              <button type="button" onClick={() => void send()} disabled={busy}
                className="rounded bg-blue-600 px-3 py-1.5 text-white disabled:opacity-50">
                보내기
              </button>
              <button type="button" onClick={() => setConfirming(false)} disabled={busy}
                className="rounded border px-3 py-1.5">
                취소
              </button>
            </div>
          </div>
        ) : (
          <button type="button" onClick={() => setConfirming(true)}
            disabled={busy || !canSubmit || notEnough}
            data-testid="transfer-review"
            className="rounded bg-blue-600 px-3 py-1.5 text-white disabled:opacity-50">
            확인하기
          </button>
        )}
      </section>

      <section className="space-y-3">
        <h2 className="font-semibold">주고받은 내역</h2>
        {history.length === 0 ? (
          <p className="text-gray-500" data-testid="transfer-history-empty">
            아직 주고받은 포인트가 없습니다.
          </p>
        ) : (
          <ul className="space-y-2">
            {history.map(entry => (
              <li key={entry.transferNo}
                data-testid={`transfer-item-${entry.transferNo}`}
                className="rounded border p-3 text-sm">
                <div className="flex items-center gap-2">
                  <span className={entry.outgoing ? 'text-red-600' : 'text-blue-600'}>
                    {entry.outgoing ? '보냄' : '받음'}
                  </span>
                  <span className="font-medium">
                    {entry.outgoing ? '-' : '+'}{formatPoint(entry.amount)}
                  </span>
                  <span className="text-gray-700">
                    {entry.outgoing ? `${entry.counterpartName} 님께` : `${entry.counterpartName} 님으로부터`}
                  </span>
                </div>
                {entry.message && <p className="mt-1 text-gray-600">“{entry.message}”</p>}
                <p className="mt-1 text-xs text-gray-500">
                  {formatMoment(entry.transferredAt)} · {entry.transferNo}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>
    </main>
  );
}
