import React, { useState } from 'react';
import {
  cashReceiptApi,
  CashReceipt,
  CashReceiptIdentifierType,
  CashReceiptPurpose,
} from '@/api/cashReceipt';
import { apiErrorMessage, apiErrorStatus } from '@/lib/apiError';

interface Props {
  orderId: number;
}

const PURPOSE_LABEL: Record<CashReceiptPurpose, string> = {
  INCOME_DEDUCTION: '소득공제 (개인)',
  EXPENSE_PROOF: '지출증빙 (사업자)',
};

/** 용도별로 쓸 수 있는 식별번호가 다르다 — 서버가 거부하기 전에 화면에서 먼저 좁힌다. */
const IDENTIFIER_BY_PURPOSE: Record<CashReceiptPurpose, { type: CashReceiptIdentifierType; label: string; placeholder: string }[]> = {
  INCOME_DEDUCTION: [
    { type: 'MOBILE', label: '휴대폰번호', placeholder: '010-1234-5678' },
    { type: 'CASH_RECEIPT_CARD', label: '현금영수증카드', placeholder: '카드 뒷면 13~19자리' },
  ],
  EXPENSE_PROOF: [
    { type: 'BUSINESS_NUMBER', label: '사업자등록번호', placeholder: '220-81-62517' },
  ],
};

const STATUS_LABEL: Record<string, string> = {
  REQUESTED: '발급 요청 중',
  ISSUED: '발급 완료',
  CANCEL_REQUESTED: '취소 요청 중',
  CANCELED: '취소됨',
  FAILED: '발급 실패',
};

const won = (v: number) => `${v.toLocaleString('ko-KR')}원`;

/**
 * 주문 1건의 현금영수증 발급/조회.
 *
 * 계좌이체·가상계좌 결제만 대상이라, 카드 주문에서 열면 서버가 400 으로 거절한다 — 그 사유를
 * 그대로 보여 준다(카드 매출은 카드사 전표로 이미 신고되어 이중 공제가 되기 때문).
 */
const CashReceiptPanel: React.FC<Props> = ({ orderId }) => {
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const [receipt, setReceipt] = useState<CashReceipt | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [purpose, setPurpose] = useState<CashReceiptPurpose>('INCOME_DEDUCTION');
  const [identifierType, setIdentifierType] = useState<CashReceiptIdentifierType>('MOBILE');
  const [identifierValue, setIdentifierValue] = useState('');

  const expand = async () => {
    setOpen(true);
    setLoading(true);
    setError(null);
    try {
      setReceipt(await cashReceiptApi.getByOrder(orderId));
    } catch (err) {
      // 404 는 "아직 발급한 적 없음" — 오류가 아니므로 조용히 넘기고 발급 폼을 띄운다.
      // 그 밖은 드러낸다. 400(카드 결제라 대상 아님)까지 삼키면 "대상 아님"과 장애가 같아 보인다.
      if (apiErrorStatus(err) !== 404) {
        setError(apiErrorMessage(err, '현금영수증 조회에 실패했습니다.'));
      }
    } finally {
      setLoading(false);
    }
  };

  const changePurpose = (next: CashReceiptPurpose) => {
    setPurpose(next);
    setIdentifierType(IDENTIFIER_BY_PURPOSE[next][0].type);
    setIdentifierValue('');
  };

  const submit = async () => {
    setLoading(true);
    setError(null);
    try {
      setReceipt(await cashReceiptApi.issueForOrder(orderId, {
        purpose, identifierType, identifierValue,
      }));
    } catch (err) {
      setError(apiErrorMessage(err, '현금영수증 발급에 실패했습니다.'));
    } finally {
      setLoading(false);
    }
  };

  if (!open) {
    return (
      <div className="mt-3 pt-3 border-t border-gray-100">
        <button
          type="button"
          onClick={expand}
          className="text-xs font-medium text-gray-500 hover:text-blue-600"
        >
          🧾 현금영수증
        </button>
      </div>
    );
  }

  const options = IDENTIFIER_BY_PURPOSE[purpose];

  return (
    <div className="mt-3 pt-3 border-t border-gray-100 space-y-3">
      <p className="text-xs font-semibold text-gray-700">🧾 현금영수증</p>

      {loading && <p className="text-xs text-gray-400">불러오는 중...</p>}

      {receipt ? (
        <div className="rounded-lg bg-gray-50 border border-gray-200 p-3 space-y-1 text-xs text-gray-700">
          <div className="flex justify-between">
            <span className="text-gray-500">상태</span>
            <span className="font-semibold">{STATUS_LABEL[receipt.status] ?? receipt.status}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">용도</span>
            <span>{receipt.purposeLabel}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">식별번호</span>
            <span>{receipt.maskedIdentifier}</span>
          </div>
          <div className="flex justify-between">
            <span className="text-gray-500">공급가액 / 부가세</span>
            <span>{won(receipt.supplyAmount)} / {won(receipt.vatAmount)}</span>
          </div>
          {receipt.approvalNumber && (
            <div className="flex justify-between">
              <span className="text-gray-500">승인번호</span>
              <span className="font-mono">{receipt.approvalNumber}</span>
            </div>
          )}
          {receipt.failureReason && (
            <p className="text-red-600 pt-1">사유: {receipt.failureReason}</p>
          )}
        </div>
      ) : (
        !loading && (
          <div className="space-y-2">
            <p className="text-[11px] text-gray-400">
              계좌이체·가상계좌 결제만 발급됩니다. 카드 결제는 카드사 매출전표로 이미 신고되어 대상이 아닙니다.
            </p>
            <div className="flex gap-2">
              {(Object.keys(PURPOSE_LABEL) as CashReceiptPurpose[]).map((p) => (
                <button
                  key={p}
                  type="button"
                  onClick={() => changePurpose(p)}
                  className={`flex-1 py-1.5 text-xs rounded-lg border transition ${
                    purpose === p
                      ? 'border-blue-400 bg-blue-50 text-blue-700 font-semibold'
                      : 'border-gray-200 text-gray-600'
                  }`}
                >
                  {PURPOSE_LABEL[p]}
                </button>
              ))}
            </div>
            <div className="flex gap-2">
              <select
                value={identifierType}
                onChange={(e) => setIdentifierType(e.target.value as CashReceiptIdentifierType)}
                className="text-xs border border-gray-200 rounded-lg px-2 py-1.5"
              >
                {options.map((o) => (
                  <option key={o.type} value={o.type}>{o.label}</option>
                ))}
              </select>
              <input
                value={identifierValue}
                onChange={(e) => setIdentifierValue(e.target.value)}
                placeholder={options.find((o) => o.type === identifierType)?.placeholder}
                className="flex-1 text-xs border border-gray-200 rounded-lg px-2 py-1.5"
              />
            </div>
            <button
              type="button"
              disabled={loading || identifierValue.trim().length === 0}
              onClick={submit}
              className="w-full py-2 text-xs font-semibold rounded-lg bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
            >
              발급 신청
            </button>
          </div>
        )
      )}

      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  );
};

export default CashReceiptPanel;
