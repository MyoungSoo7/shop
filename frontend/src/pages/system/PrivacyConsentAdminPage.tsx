import { useCallback, useEffect, useState } from 'react';
import {
  CONSENT_TYPE_LABEL,
  adminPrivacyConsentApi,
  privacyConsentApi,
  type AdminOrderPrivacyConsent,
  type PrivacyConsentTerms,
} from '@/api/privacyConsent';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 주문 시점 개인정보 동의 이력 콘솔 — <b>읽기 전용</b>이다.
 *
 * <p><b>왜 이 화면이 필요한가.</b> 동의는 주문과 같은 트랜잭션에서 기록되지만, 그 기록을 읽는
 * 경로가 운영자에게 없으면 두 가지가 불가능해진다. 하나는 정보주체가 "내가 무엇에 동의했는지
 * 보여 달라"고 요구할 때 답하는 일이고(열람 요구권), 다른 하나는 문안을 고친 뒤 <b>옛 버전으로
 * 동의한 사람이 남아 있는가</b>를 세는 일이다. 남기기만 하고 볼 수 없는 기록은 증적이 아니다 —
 * 감사 로그 콘솔이 생긴 것과 같은 이유다.
 *
 * <p><b>축이 둘인 이유.</b> 사람으로 찾는 것과 문안 버전으로 찾는 것은 답해야 하는 질문이
 * 다르다. 앞은 "이 사람에게 무엇을 물었나", 뒤는 "이 문장에 동의한 사람이 누구인가"다. 서버도
 * 두 질의를 따로 두었으므로(`?userId=` / `?termsCode=&termsVersion=`) 화면도 합치지 않는다 —
 * 합친 표는 어느 질문에도 정확히 답하지 못한다.
 *
 * <p><b>고치는 버튼이 없는 것도 의도다.</b> 운영자가 동의 이력을 수정할 수 있으면 그 이력은 더
 * 이상 증거가 아니다. 서버에도 쓰기 경로가 아예 없다.
 */

const LIMIT = 100;

type Axis = 'USER' | 'TERMS';

const formatAt = (value: string): string => {
  const at = new Date(value);
  return Number.isNaN(at.getTime()) ? value : at.toLocaleString('ko-KR');
};

export default function PrivacyConsentAdminPage() {
  const [axis, setAxis] = useState<Axis>('USER');

  const [userId, setUserId] = useState('');
  const [termsCode, setTermsCode] = useState('');
  const [termsVersion, setTermsVersion] = useState('');

  const [rows, setRows] = useState<AdminOrderPrivacyConsent[] | null>(null);
  const [currentTerms, setCurrentTerms] = useState<PrivacyConsentTerms[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  /**
   * 현행 문안을 힌트로 띄운다 — 이 화면에서 찾는 것은 대개 <b>현행이 아닌</b> 버전이라,
   * 지금 몇 번인지를 모르면 무엇을 물어야 하는지도 알 수 없다. 실패해도 조회는 되므로
   * 이 실패로 화면을 막지 않는다.
   */
  useEffect(() => {
    let cancelled = false;
    void privacyConsentApi
      .terms()
      .then(list => {
        if (!cancelled) setCurrentTerms(list);
      })
      .catch(() => {
        if (!cancelled) setCurrentTerms([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const search = useCallback(async () => {
    setError(null);
    setLoading(true);
    try {
      const result =
        axis === 'USER'
          ? await adminPrivacyConsentApi.ofUser(Number(userId), LIMIT)
          : await adminPrivacyConsentApi.ofTermsVersion(termsCode.trim(), Number(termsVersion), LIMIT);
      setRows(result);
    } catch (err) {
      setError(apiErrorMessage(err, '동의 이력을 불러오지 못했습니다.'));
      setRows(null);
    } finally {
      setLoading(false);
    }
  }, [axis, userId, termsCode, termsVersion]);

  const canSearch =
    axis === 'USER'
      ? Number(userId) > 0
      : termsCode.trim().length > 0 && Number(termsVersion) > 0;

  return (
    <main className="space-y-6 p-6">
      <header>
        <h1 className="text-2xl font-bold">개인정보 동의 이력</h1>
        <p className="text-sm text-gray-500">
          주문 시점에 어떤 문안으로 동의를 받았는지 조회합니다. 읽기 전용이며 수정 경로는 없습니다.
        </p>
      </header>

      <div role="tablist" aria-label="조회 축" className="flex gap-2">
        <button
          type="button"
          role="tab"
          aria-selected={axis === 'USER'}
          onClick={() => { setAxis('USER'); setRows(null); setError(null); }}
          className={`rounded px-4 py-2 text-sm ${axis === 'USER' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-700'}`}
        >
          사용자별
          <span className="ml-2 text-xs opacity-75">이 사람에게 무엇을 물었나</span>
        </button>
        <button
          type="button"
          role="tab"
          aria-selected={axis === 'TERMS'}
          onClick={() => { setAxis('TERMS'); setRows(null); setError(null); }}
          className={`rounded px-4 py-2 text-sm ${axis === 'TERMS' ? 'bg-blue-600 text-white' : 'bg-gray-100 text-gray-700'}`}
        >
          문안 버전별
          <span className="ml-2 text-xs opacity-75">이 문장에 동의한 사람이 누구인가</span>
        </button>
      </div>

      <section className="grid gap-3 rounded border p-4 sm:grid-cols-3">
        {axis === 'USER' ? (
          <label className="flex flex-col gap-1">
            <span className="text-sm">사용자 ID</span>
            <input
              aria-label="사용자 ID"
              type="number"
              min={1}
              value={userId}
              onChange={e => setUserId(e.target.value)}
              className="rounded border px-3 py-2"
            />
          </label>
        ) : (
          <>
            <label className="flex flex-col gap-1">
              <span className="text-sm">문안 코드</span>
              <input
                aria-label="문안 코드"
                value={termsCode}
                onChange={e => setTermsCode(e.target.value)}
                placeholder="THIRD_PARTY_DELIVERY"
                className="rounded border px-3 py-2"
              />
            </label>
            <label className="flex flex-col gap-1">
              <span className="text-sm">버전</span>
              <input
                aria-label="버전"
                type="number"
                min={1}
                value={termsVersion}
                onChange={e => setTermsVersion(e.target.value)}
                className="rounded border px-3 py-2"
              />
            </label>
          </>
        )}

        <div className="flex items-end gap-2 sm:col-span-3">
          <button
            type="button"
            onClick={() => void search()}
            disabled={!canSearch || loading}
            className="rounded bg-blue-600 px-4 py-2 text-white disabled:opacity-50"
          >
            {loading ? '조회 중…' : '조회'}
          </button>
          <span className="text-xs text-gray-500">최근 {LIMIT}건까지 표시합니다.</span>
        </div>
      </section>

      {axis === 'TERMS' && currentTerms.length > 0 && (
        <section aria-label="현행 문안" className="space-y-1">
          <p className="text-xs text-gray-500">
            현행 문안(눌러서 채우기). 여기 적힌 버전보다 <b>낮은 버전</b>으로 동의한 사람에게는 지금
            문안에 대한 동의가 없습니다 — 재동의를 받아야 합니다.
          </p>
          <div className="flex flex-wrap gap-2">
            {currentTerms.map(t => (
              <button
                key={t.code}
                type="button"
                onClick={() => { setTermsCode(t.code); setTermsVersion(String(t.version)); }}
                className="rounded-full bg-gray-100 px-3 py-1 text-xs text-gray-700 hover:bg-gray-200"
              >
                {t.code} <b>v{t.version}</b>
              </button>
            ))}
          </div>
        </section>
      )}

      {error && <p role="alert" className="text-red-600">{error}</p>}

      {rows && (
        <section>
          <p className="mb-2 text-sm text-gray-600">총 {rows.length.toLocaleString()}건</p>

          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="bg-gray-50">
                <tr>
                  <th className="px-3 py-2">동의 시각</th>
                  <th className="px-3 py-2">주문</th>
                  <th className="px-3 py-2">사용자</th>
                  <th className="px-3 py-2">문안</th>
                  <th className="px-3 py-2">유형</th>
                  <th className="px-3 py-2">동의</th>
                  <th className="px-3 py-2">제공받는 자</th>
                  <th className="px-3 py-2">접속지</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(row => (
                  <tr key={`${row.orderId}-${row.termsCode}-${row.termsVersion}`} className="border-t align-top">
                    <td className="whitespace-nowrap px-3 py-2">{formatAt(row.agreedAt)}</td>
                    <td className="px-3 py-2">#{row.orderId}</td>
                    <td className="px-3 py-2">#{row.userId}</td>
                    <td className="px-3 py-2">
                      {row.termsCode} v{row.termsVersion}
                      {!row.bodyUnchanged && (
                        // 버전을 올리지 않고 문장을 고쳤다는 뜻이다. 감추면 그 사실을 아무도 모르게 된다.
                        <span className="ml-2 text-xs text-amber-700">문안 변경됨</span>
                      )}
                    </td>
                    <td className="px-3 py-2">{CONSENT_TYPE_LABEL[row.consentType] ?? row.consentType}</td>
                    <td className={`px-3 py-2 font-medium ${row.agreed ? 'text-blue-600' : 'text-gray-400'}`}>
                      {row.agreed ? '동의' : '거절'}
                    </td>
                    <td className="px-3 py-2">{row.recipient ?? '—'}</td>
                    <td className="px-3 py-2">{row.ipAddress ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {rows.length === 0 && (
            // "동의하지 않았다"가 아니라 "그 조건으로 기록된 것이 없다"이다 — 없는 거절을 지어내지 않는다.
            <p className="py-6 text-center text-gray-500">조건에 맞는 동의 기록이 없습니다.</p>
          )}
        </section>
      )}
    </main>
  );
}
