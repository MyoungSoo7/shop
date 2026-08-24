import React, { useCallback, useEffect, useState } from 'react';
import {
  shippingPolicyApi,
  SellerShippingPolicy,
  describeThreshold,
  formatWon,
} from '@/api/shippingPolicy';
import Spinner from '@/components/Spinner';
import { errorDetail } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';

/**
 * 셀러 배송비 정책 콘솔 — 기본배송비와 무료배송 임계를 셀러별로 건다.
 *
 * <p>이 화면이 없으면 정책은 DB 로만 넣을 수 있고, 그러면 배송비 계산은 코드로만 존재하는
 * 기능이 된다(셀러에게 정책이 없으면 기본배송비는 0 원으로 계산된다 — 조용히 무료배송이다).
 *
 * <p><b>무료배송 조건 없음(null)과 임계 0(항상 무료)은 서로 반대다.</b> 입력 폼이 이 둘을
 * 하나의 빈 칸으로 뭉개면 운영자는 반대 뜻을 저장하게 된다. 그래서 체크박스로 상태를 먼저
 * 고르게 하고, 목록에서도 문장으로 풀어 보여 준다.
 */

interface FormState {
  sellerId: string;
  baseFee: string;
  /** 체크되어 있으면 임계 자체를 보내지 않는다(= 무료배송 조건 없음) */
  noFreeShipping: boolean;
  freeThreshold: string;
}

const EMPTY_FORM: FormState = {
  sellerId: '',
  baseFee: '',
  noFreeShipping: false,
  freeThreshold: '',
};

const isNonNegativeNumber = (value: string): boolean =>
  value.trim() !== '' && /^\d+(\.\d+)?$/.test(value.trim());

const ShippingPolicyAdminPage: React.FC = () => {
  const [policies, setPolicies] = useState<SellerShippingPolicy[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  /** 수정 중인 셀러 — 값이 있으면 셀러 ID 칸을 잠근다(정책 키를 실수로 옮기지 못하게) */
  const [editing, setEditing] = useState<number | null>(null);
  const { showToast } = useToast();

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPolicies(await shippingPolicyApi.list());
    } catch (err) {
      setError(errorDetail(err, '배송비 정책을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const startEdit = (policy: SellerShippingPolicy) => {
    setEditing(policy.sellerId);
    setForm({
      sellerId: String(policy.sellerId),
      baseFee: policy.baseFee,
      noFreeShipping: policy.freeThreshold === null,
      freeThreshold: policy.freeThreshold ?? '',
    });
  };

  const cancelEdit = () => {
    setEditing(null);
    setForm(EMPTY_FORM);
  };

  // 서버 도메인이 음수를 400 으로 막지만, 형식 오류까지 왕복시킬 이유는 없다.
  const valid =
    /^\d+$/.test(form.sellerId.trim()) &&
    isNonNegativeNumber(form.baseFee) &&
    (form.noFreeShipping || isNonNegativeNumber(form.freeThreshold));

  const submit = async () => {
    if (!valid) return;
    setSaving(true);
    try {
      await shippingPolicyApi.upsert(Number(form.sellerId), {
        baseFee: form.baseFee.trim(),
        freeThreshold: form.noFreeShipping ? null : form.freeThreshold.trim(),
      });
      showToast('배송비 정책을 저장했습니다.', 'success');
      cancelEdit();
      await load();
    } catch (err) {
      showToast(errorDetail(err, '배송비 정책을 저장하지 못했습니다.'), 'error');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-gray-900">배송비 정책</h1>
        <p className="text-sm text-gray-500 mt-1">
          셀러별 기본배송비와 무료배송 임계. 주문 금액이 임계 이상이면 그 셀러의 기본배송비는 면제됩니다.
        </p>
      </div>

      <div className="bg-amber-50 border border-amber-200 rounded-xl p-4 text-sm text-amber-900">
        <p className="font-semibold">정책이 없는 셀러는 기본배송비가 0원입니다.</p>
        <p className="mt-1 text-amber-800">
          목록에 없는 셀러의 주문에는 기본배송비가 붙지 않습니다. 상품에 개별 배송비가 걸려 있으면 그것은 정책과 무관하게 따로 부과됩니다.
        </p>
      </div>

      {/* ── 등록 · 변경 폼 ─────────────────────────────── */}
      <div className="bg-white rounded-xl border border-gray-200 p-5">
        <h2 className="font-semibold text-gray-900">
          {editing === null ? '정책 등록' : `셀러 ${editing} 정책 변경`}
        </h2>

        <div className="mt-4 grid gap-3 sm:grid-cols-3">
          <label className="text-sm text-gray-700">
            셀러 ID
            <input
              aria-label="셀러 ID"
              value={form.sellerId}
              disabled={editing !== null}
              onChange={(e) => setForm((p) => ({ ...p, sellerId: e.target.value }))}
              placeholder="예: 77"
              className="mt-1 w-full border border-gray-300 rounded px-2 py-1.5 text-sm disabled:bg-gray-100"
            />
          </label>

          <label className="text-sm text-gray-700">
            기본배송비 (원)
            <input
              aria-label="기본배송비"
              value={form.baseFee}
              onChange={(e) => setForm((p) => ({ ...p, baseFee: e.target.value }))}
              placeholder="예: 3000"
              className="mt-1 w-full border border-gray-300 rounded px-2 py-1.5 text-sm"
            />
          </label>

          <label className="text-sm text-gray-700">
            무료배송 임계 (원)
            <input
              aria-label="무료배송 임계"
              value={form.freeThreshold}
              disabled={form.noFreeShipping}
              onChange={(e) => setForm((p) => ({ ...p, freeThreshold: e.target.value }))}
              placeholder="예: 50000"
              className="mt-1 w-full border border-gray-300 rounded px-2 py-1.5 text-sm disabled:bg-gray-100"
            />
          </label>
        </div>

        <label className="flex items-center gap-2 mt-3 text-sm text-gray-700">
          <input
            type="checkbox"
            checked={form.noFreeShipping}
            onChange={(e) => setForm((p) => ({ ...p, noFreeShipping: e.target.checked }))}
          />
          무료배송 조건 없음 (금액과 무관하게 항상 부과)
        </label>
        <p className="text-xs text-gray-500 mt-1">
          임계를 <b>0</b> 으로 저장하면 정반대인 &quot;항상 무료&quot; 가 됩니다.
        </p>

        <div className="mt-4 flex gap-2">
          <button
            disabled={!valid || saving}
            onClick={() => void submit()}
            className="px-3 py-1.5 text-sm font-semibold rounded bg-gray-900 text-white disabled:opacity-40"
          >
            {saving ? '저장 중...' : '저장'}
          </button>
          {editing !== null && (
            <button
              onClick={cancelEdit}
              className="px-3 py-1.5 text-sm rounded border border-gray-300 text-gray-600"
            >
              취소
            </button>
          )}
        </div>
      </div>

      {/* ── 목록 ───────────────────────────────────────── */}
      {loading ? (
        <Spinner size="md" message="배송비 정책 불러오는 중..." />
      ) : error ? (
        <p className="text-center text-red-600 py-8">{error}</p>
      ) : policies.length === 0 ? (
        <div className="text-center py-16 text-gray-400 bg-white rounded-xl border border-gray-200">
          <p className="text-sm">등록된 배송비 정책이 없습니다 — 모든 셀러의 기본배송비가 0원입니다.</p>
        </div>
      ) : (
        <div className="bg-white rounded-xl border border-gray-200 overflow-x-auto">
          <table className="min-w-full text-sm">
            <thead className="bg-gray-50 text-gray-600">
              <tr>
                <th className="text-left px-4 py-2 font-medium">셀러 ID</th>
                <th className="text-right px-4 py-2 font-medium">기본배송비</th>
                <th className="text-left px-4 py-2 font-medium">무료배송 조건</th>
                <th className="px-4 py-2" />
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {policies.map((policy) => (
                <tr key={policy.sellerId}>
                  <td className="px-4 py-2 font-mono">{policy.sellerId}</td>
                  <td className="px-4 py-2 text-right">{formatWon(policy.baseFee)}</td>
                  <td className="px-4 py-2 text-gray-700">{describeThreshold(policy.freeThreshold)}</td>
                  <td className="px-4 py-2 text-right">
                    <button
                      onClick={() => startEdit(policy)}
                      className="px-2 py-1 text-xs rounded border border-gray-300 text-gray-700"
                    >
                      변경
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
};

export default ShippingPolicyAdminPage;
