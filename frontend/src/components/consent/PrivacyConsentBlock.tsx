import React, { useState } from 'react';
import { CONSENT_TYPE_LABEL, PrivacyConsentTerms } from '@/api/privacyConsent';

/**
 * 결제 화면의 개인정보 동의 구획.
 *
 * <p><b>왜 생겼나.</b> 그전까지 이 자리에는 아무것도 없었다. 그런데 주문이 성립하면 이름·연락처·
 * 주소가 택배사로 넘어간다 — 제3자 제공이다. 개인정보 보호법 제17조는 <b>제공받는 자·제공 목적·
 * 제공 항목·보유 기간</b> 넷을 알리고 동의를 받으라고 한다. 그래서 이 구획은 체크박스만 두지 않고
 * 그 넷을 표로 함께 보여 준다 — "동의합니다"만 있고 무엇에 동의하는지가 없으면 받아 둔 것이
 * 동의로서 성립하지 않는다.
 *
 * <p>필수와 선택을 눈에 띄게 갈라 놓은 것도 같은 이유다(제22조). 선택 항목을 필수처럼 보이게 해
 * 함께 받아 두는 것이 이 법이 막으려는 바로 그 형태다. 여기서는 선택 항목을 체크하지 않아도
 * 주문 버튼이 열린다.
 *
 * <p>상태는 {@code lib/usePrivacyConsent} 훅이 들고 있다. 이 컴포넌트는 그리기만 한다 —
 * 주문 요청을 만드는 것은 결제 화면의 일이다.
 */
export interface PrivacyConsentBlockProps {
  terms: PrivacyConsentTerms[];
  agreed: Record<string, boolean>;
  onToggle: (code: string, next: boolean) => void;
  loading?: boolean;
  error?: string | null;
  disabled?: boolean;
}

const PrivacyConsentBlock: React.FC<PrivacyConsentBlockProps> = ({
  terms, agreed, onToggle, loading, error, disabled,
}) => {
  const [expanded, setExpanded] = useState<string | null>(null);

  if (loading) {
    return (
      <div className="space-y-3">
        <h3 className="font-bold text-gray-900">개인정보 수집·제공 동의</h3>
        <p className="text-sm text-gray-400">동의 문안을 불러오는 중...</p>
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-3">
        <h3 className="font-bold text-gray-900">개인정보 수집·제공 동의</h3>
        <div className="bg-red-50 border border-red-200 rounded-lg p-3">
          <p className="text-red-800 text-xs">{error}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      <h3 className="font-bold text-gray-900">개인정보 수집·제공 동의</h3>

      <div className="space-y-2">
        {terms.map((t) => {
          const open = expanded === t.code;
          return (
            <div key={t.code} className="border border-gray-200 rounded-lg">
              <div className="flex items-start gap-2 p-3">
                <input
                  id={`consent-${t.code}`}
                  type="checkbox"
                  checked={agreed[t.code] === true}
                  onChange={(e) => onToggle(t.code, e.target.checked)}
                  disabled={disabled}
                  className="mt-0.5 h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 disabled:opacity-40"
                />
                <div className="flex-1 min-w-0">
                  <label htmlFor={`consent-${t.code}`} className="block text-sm text-gray-900 cursor-pointer">
                    <span className={t.required ? 'text-red-600 font-semibold' : 'text-gray-400'}>
                      [{t.required ? '필수' : '선택'}]
                    </span>{' '}
                    {t.title}
                  </label>
                  <p className="text-xs text-gray-400 mt-0.5">
                    {CONSENT_TYPE_LABEL[t.consentType] ?? t.consentType} · v{t.version}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setExpanded(open ? null : t.code)}
                  className="text-xs text-gray-500 hover:text-gray-800 flex-shrink-0 underline"
                >
                  {open ? '접기' : '자세히'}
                </button>
              </div>

              {open && (
                <div className="border-t border-gray-100 bg-gray-50 p-3 space-y-2">
                  {/* 제17조가 알리라고 하는 넷. 전문을 접어 둔 채 이것만 봐도 판단할 수 있어야 한다. */}
                  <dl className="text-xs space-y-1">
                    {t.recipient && (
                      <div className="flex gap-2">
                        <dt className="text-gray-500 w-20 flex-shrink-0">제공받는 자</dt>
                        <dd className="text-gray-800">{t.recipient}</dd>
                      </div>
                    )}
                    <div className="flex gap-2">
                      <dt className="text-gray-500 w-20 flex-shrink-0">목적</dt>
                      <dd className="text-gray-800">{t.purpose}</dd>
                    </div>
                    <div className="flex gap-2">
                      <dt className="text-gray-500 w-20 flex-shrink-0">항목</dt>
                      <dd className="text-gray-800">{t.providedItems}</dd>
                    </div>
                    <div className="flex gap-2">
                      <dt className="text-gray-500 w-20 flex-shrink-0">보유·이용</dt>
                      <dd className="text-gray-800">{t.retention}</dd>
                    </div>
                  </dl>
                  <p className="text-xs text-gray-600 whitespace-pre-line pt-2 border-t border-gray-200">
                    {t.body}
                  </p>
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* 거부할 수 있다는 사실과 그 결과를 함께 적는다 — 결과를 안 적으면 고지가 아니다. */}
      <p className="text-xs text-gray-500">
        필수 항목은 주문 처리에 필요한 최소한이라 동의하지 않으면 주문할 수 없습니다.
        선택 항목은 동의하지 않아도 주문에는 영향이 없습니다.
      </p>
    </div>
  );
};

export default PrivacyConsentBlock;
