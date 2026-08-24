import { useState } from 'react';
import { giftCardApi, type ExpireGiftCardsResult, type IssuedGiftCard } from '@/api/giftCard';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 기프트카드 운영 콘솔.
 *
 * <p><b>이 화면의 핵심 제약</b>: 발행 응답의 코드는 <b>다시 볼 수 없다.</b> 서버에는 해시만
 * 남으므로, 화면을 새로고침하거나 탭을 닫으면 그 카드는 배포할 수 없는 종이 조각이 된다.
 * 그래서 발행 직후 CSV 내려받기를 바로 제공하고, 아직 저장하지 않았음을 계속 경고한다.
 *
 * <p>소멸은 포인트와 같은 규약 — 미리보기를 먼저 돌려야 실행 버튼이 열린다.
 */
export default function GiftCardConsolePage() {
  const [quantity, setQuantity] = useState('10');
  const [faceAmount, setFaceAmount] = useState('50000');
  const [validityDays, setValidityDays] = useState('365');
  const [activate, setActivate] = useState(true);
  const [memo, setMemo] = useState('');

  const [issuing, setIssuing] = useState(false);
  const [issued, setIssued] = useState<IssuedGiftCard[] | null>(null);
  const [downloaded, setDownloaded] = useState(false);
  const [issueError, setIssueError] = useState<string | null>(null);

  const [preview, setPreview] = useState<ExpireGiftCardsResult | null>(null);
  const [expiryResult, setExpiryResult] = useState<ExpireGiftCardsResult | null>(null);
  const [expiryBusy, setExpiryBusy] = useState(false);
  const [expiryError, setExpiryError] = useState<string | null>(null);

  const issue = async () => {
    setIssueError(null);
    setIssuing(true);
    try {
      const result = await giftCardApi.issue({
        quantity: Number(quantity),
        faceAmount: Number(faceAmount),
        validityDays: Number(validityDays),
        activate,
        memo: memo.trim() === '' ? undefined : memo.trim(),
      });
      setIssued(result);
      setDownloaded(false);
    } catch (err) {
      setIssueError(apiErrorMessage(err, '기프트카드 발행에 실패했습니다.'));
    } finally {
      setIssuing(false);
    }
  };

  /** 코드를 CSV 로 내려받는다. 이 화면을 벗어나면 코드를 다시 얻을 방법이 없다. */
  const downloadCsv = () => {
    if (!issued) return;
    const rows = ['giftCardId,code,faceAmount', ...issued.map(
      card => `${card.giftCardId},${card.code},${card.faceAmount}`)];
    const blob = new Blob([rows.join('\n')], { type: 'text/csv;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `gift-cards-${Date.now()}.csv`;
    link.click();
    URL.revokeObjectURL(url);
    setDownloaded(true);
  };

  const runPreview = async () => {
    setExpiryError(null);
    setExpiryResult(null);
    setExpiryBusy(true);
    try {
      setPreview(await giftCardApi.runExpiry(true));
    } catch (err) {
      setExpiryError(apiErrorMessage(err, '소멸 미리보기에 실패했습니다.'));
    } finally {
      setExpiryBusy(false);
    }
  };

  const runExpiry = async () => {
    setExpiryError(null);
    setExpiryBusy(true);
    try {
      setExpiryResult(await giftCardApi.runExpiry(false));
      setPreview(null);
    } catch (err) {
      setExpiryError(apiErrorMessage(err, '소멸 실행에 실패했습니다.'));
    } finally {
      setExpiryBusy(false);
    }
  };

  return (
    <main className="p-6 space-y-8">
      <header>
        <h1 className="text-2xl font-bold">기프트카드 운영</h1>
        <p className="text-sm text-gray-500">
          상품권을 발행하고 유효기간 소멸을 실행합니다. 발행된 코드는 서버에 평문으로 저장되지 않습니다.
        </p>
      </header>

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">발행</h2>

        <div className="grid gap-3 sm:grid-cols-2">
          <label className="flex flex-col gap-1">
            <span className="text-sm">장수</span>
            <input aria-label="장수" value={quantity} onChange={e => setQuantity(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">권면가</span>
            <input aria-label="권면가" value={faceAmount} onChange={e => setFaceAmount(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1">
            <span className="text-sm">유효기간(일)</span>
            <input aria-label="유효기간" value={validityDays} onChange={e => setValidityDays(e.target.value)}
              inputMode="numeric" className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col gap-1 sm:col-span-2">
            <span className="text-sm">메모</span>
            <input aria-label="메모" value={memo} onChange={e => setMemo(e.target.value)}
              placeholder="8월 프로모션" className="rounded border px-3 py-2" />
          </label>
        </div>

        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" aria-label="즉시 활성화" checked={activate}
            onChange={e => setActivate(e.target.checked)} />
          즉시 활성화 (끄면 발행만 하고 등록은 막습니다 — 유출된 코드가 곧 잔액이 되지 않게)
        </label>

        <button type="button" onClick={() => void issue()} disabled={issuing}
          className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50">
          {issuing ? '발행 중…' : '기프트카드 발행'}
        </button>

        {issueError && <p role="alert" className="text-red-600">{issueError}</p>}

        {issued && (
          <div className="space-y-2 rounded bg-amber-50 p-3">
            {!downloaded && (
              <p role="alert" className="font-semibold text-amber-800">
                아직 저장하지 않았습니다. 이 코드는 지금 화면을 벗어나면 다시 볼 수 없습니다.
              </p>
            )}
            <button type="button" onClick={downloadCsv}
              className="rounded bg-slate-700 px-3 py-2 text-white">
              코드 CSV 내려받기
            </button>
            <table className="w-full text-sm">
              <thead>
                <tr><th className="text-left">카드 ID</th><th className="text-left">코드</th><th className="text-left">권면가</th></tr>
              </thead>
              <tbody>
                {issued.map(card => (
                  <tr key={card.giftCardId}>
                    <td>{card.giftCardId}</td>
                    <td className="font-mono">{card.code}</td>
                    <td>{card.faceAmount.toLocaleString()}원</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      <section className="space-y-3 rounded border p-4">
        <h2 className="text-lg font-semibold">유효기간 소멸</h2>
        <p className="text-sm text-gray-500">
          만료된 카드의 잔액을 소멸시킵니다. 미리보기를 먼저 실행해야 소멸 버튼이 열립니다.
        </p>

        <div className="flex flex-wrap gap-2">
          <button type="button" onClick={() => void runPreview()} disabled={expiryBusy}
            className="rounded border px-4 py-2 disabled:opacity-50">
            {expiryBusy ? '실행 중…' : '미리보기'}
          </button>
          <button type="button" onClick={() => void runExpiry()} disabled={expiryBusy || preview === null}
            className="rounded bg-red-600 px-4 py-2 text-white disabled:opacity-50">
            소멸 실행
          </button>
        </div>
        {preview === null && expiryResult === null && (
          <p className="text-xs text-gray-500">미리보기를 먼저 실행하면 소멸 버튼이 열립니다.</p>
        )}

        {expiryError && <p role="alert" className="text-red-600">{expiryError}</p>}
        {preview && (
          <p role="status" className="text-sm">
            미리보기: 카드 {preview.cardCount}장 ·
            소멸 예정 {preview.forfeitedTotal.toLocaleString()}원
          </p>
        )}
        {expiryResult && (
          <p role="status" className="text-sm text-red-700">
            소멸 완료: 카드 {expiryResult.cardCount}장 ·
            소멸액 {expiryResult.forfeitedTotal.toLocaleString()}원
          </p>
        )}
      </section>
    </main>
  );
}
