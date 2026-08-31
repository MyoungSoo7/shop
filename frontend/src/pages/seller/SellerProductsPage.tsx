import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  MEMBER_ROLE_LABEL,
  displayStatus,
  formatMoney,
  sellerApi,
  type SellerMember,
  type SellerProfile,
  type Submission,
  type SubmissionInput,
  type SubmissionPage,
  type SubmissionStatus,
} from '@/api/seller';
import { apiErrorMessage, apiErrorStatus } from '@/lib/apiError';

/**
 * 셀러 상품 등록 — 이 콘솔의 존재 이유.
 *
 * <p><b>등록이 두 걸음인 것이 화면에도 보여야 한다.</b> 레퍼런스(ssgb2e-outbackoffice)는 저장
 * 버튼 하나가 곧 심사 대기여서, 쓰다 만 신청서가 운영자 큐에 섞였다. 여기서는 저장(작성 중)과
 * 제출(심사 대기)이 다른 버튼이고, 화면이 그 둘을 같은 색·같은 자리에 두지 않는다.
 *
 * <p><b>승인 직후를 "판매중" 으로 그리지 않는다.</b> 승인은 카탈로그 등록 <i>요청</i>이고, 실제
 * 상품은 order-service 가 이벤트를 받아 만든다. 그 사이의 상태를 '등록 처리 중' 으로 따로
 * 그리지 않으면, 등록이 실패해 영영 상품이 안 생긴 건과 몇 초 뒤 생길 건이 화면에서 똑같이
 * 보인다 — 아무도 이상함을 눈치채지 못하는 실패다.
 *
 * <p><b>STAFF 에게는 제출 버튼을 그리지 않는다.</b> 다만 그건 표시일 뿐이고 실제 차단은 서버가
 * 매 요청마다 다시 한다({@code canSubmit} 은 서버가 계산해 내려 준 사본이다).
 */

const PAGE_SIZE = 20;

const STATUS_FILTERS: { value: SubmissionStatus | null; label: string }[] = [
  { value: null, label: '전체' },
  { value: 'DRAFT', label: '작성 중' },
  { value: 'SUBMITTED', label: '심사 대기' },
  { value: 'APPROVED', label: '승인' },
  { value: 'REJECTED', label: '반려' },
];

const EMPTY_FORM: SubmissionInput = {
  name: '',
  description: '',
  price: 0,
  stock: 0,
  category: '',
  imageUrl: '',
  displayVisible: true,
};

/** 빈 문자열은 "안 적었다" 이지 빈 값이 아니다 — 서버에는 null 로 보낸다. */
const blankToNull = (value: string): string | null => (value.trim() === '' ? null : value);

/** 고칠 수 있는 상태인가. 심사 중인 신청서를 고치면 운영자가 본 것과 다른 것이 승인된다. */
const editable = (submission: Submission): boolean =>
  submission.status === 'DRAFT' || submission.status === 'REJECTED';

function StatusBadge({ submission }: { submission: Submission }) {
  const tone = submission.status === 'REJECTED'
    ? 'bg-red-100 text-red-800'
    : submission.status === 'APPROVED'
      ? (submission.awaitingCatalog ? 'bg-amber-100 text-amber-800' : 'bg-green-100 text-green-800')
      : submission.status === 'SUBMITTED'
        ? 'bg-blue-100 text-blue-800'
        : 'bg-gray-100 text-gray-700';
  return (
    <span className={`rounded px-2 py-0.5 text-xs ${tone}`} data-testid={`submission-status-${submission.submissionId}`}>
      {displayStatus(submission)}
    </span>
  );
}

function SubmissionForm({
  value, onChange, onSave, onSubmit, saving, canSubmit, editing,
}: {
  value: SubmissionInput;
  onChange: (next: SubmissionInput) => void;
  onSave: () => void;
  onSubmit: (() => void) | null;
  saving: boolean;
  canSubmit: boolean;
  editing: Submission | null;
}) {
  const field = 'w-full rounded border border-gray-300 px-2 py-1 text-sm';
  return (
    <section className="rounded-lg bg-white p-4 shadow" data-testid="submission-form">
      <h2 className="font-semibold text-gray-900">
        {editing === null ? '새 상품 등록' : `신청서 ${editing.submissionId} 수정`}
      </h2>
      <div className="mt-3 grid gap-3 sm:grid-cols-2">
        <label className="text-sm text-gray-700">
          상품명
          <input type="text" value={value.name} data-testid="form-name" className={field}
                 onChange={(e) => onChange({ ...value, name: e.target.value })} />
        </label>
        <label className="text-sm text-gray-700">
          분류
          <input type="text" value={value.category ?? ''} data-testid="form-category" className={field}
                 placeholder="예: 식품" onChange={(e) => onChange({ ...value, category: e.target.value })} />
        </label>
        <label className="text-sm text-gray-700">
          판매가
          <input type="number" min={0} value={value.price} data-testid="form-price" className={field}
                 onChange={(e) => onChange({ ...value, price: Number(e.target.value) })} />
        </label>
        <label className="text-sm text-gray-700">
          재고
          <input type="number" min={0} value={value.stock} data-testid="form-stock" className={field}
                 onChange={(e) => onChange({ ...value, stock: Number(e.target.value) })} />
        </label>
        <label className="text-sm text-gray-700 sm:col-span-2">
          설명
          <textarea rows={3} value={value.description ?? ''} data-testid="form-description" className={field}
                    onChange={(e) => onChange({ ...value, description: e.target.value })} />
        </label>
        <label className="text-sm text-gray-700 sm:col-span-2">
          이미지 URL
          <input type="text" value={value.imageUrl ?? ''} data-testid="form-image" className={field}
                 onChange={(e) => onChange({ ...value, imageUrl: e.target.value })} />
        </label>
        <label className="flex items-center gap-2 text-sm text-gray-700">
          <input type="checkbox" checked={value.displayVisible} data-testid="form-visible"
                 onChange={(e) => onChange({ ...value, displayVisible: e.target.checked })} />
          진열에 노출
        </label>
      </div>

      {/* 분류·이미지는 지금 카탈로그에 반영되지 않는다. 화면이 입력만 받고 조용히 버리면
          셀러는 반영된 줄 안다 — 적어 두는 편이 낫다. */}
      <p className="mt-2 text-xs text-gray-500">
        분류와 이미지는 신청서에 함께 보관되지만, 승인 시 몰 카탈로그에는 아직 반영되지 않습니다.
      </p>

      <div className="mt-3 flex flex-wrap gap-2">
        <button type="button" onClick={onSave} disabled={saving} data-testid="form-save"
                className="rounded border border-gray-300 px-3 py-1.5 text-sm text-gray-800 disabled:opacity-50">
          {saving ? '저장 중…' : '저장 (작성 중)'}
        </button>
        {onSubmit !== null && canSubmit && (
          // 제출은 되돌릴 수 없다 — 반려돼야 다시 고칠 수 있다. 그래서 저장과 색을 나눈다.
          <button type="button" onClick={onSubmit} disabled={saving} data-testid="form-submit"
                  className="rounded bg-blue-600 px-3 py-1.5 text-sm text-white hover:bg-blue-700 disabled:opacity-50">
            심사 요청
          </button>
        )}
        {onSubmit !== null && !canSubmit && (
          <span className="self-center text-xs text-gray-500" data-testid="form-submit-blocked">
            심사 요청은 대표 · 관리자만 할 수 있습니다.
          </span>
        )}
      </div>
    </section>
  );
}

export default function SellerProductsPage() {
  const [profile, setProfile] = useState<SellerProfile | null>(null);
  const [members, setMembers] = useState<SellerMember[]>([]);
  const [result, setResult] = useState<SubmissionPage | null>(null);
  const [status, setStatus] = useState<SubmissionStatus | null>(null);
  const [page, setPage] = useState(0);
  const [form, setForm] = useState<SubmissionInput>(EMPTY_FORM);
  const [editing, setEditing] = useState<Submission | null>(null);
  const [saving, setSaving] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [blocked, setBlocked] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const loadList = useCallback(async (nextStatus: SubmissionStatus | null, nextPage: number) => {
    setResult(await sellerApi.submissions(nextStatus, nextPage, PAGE_SIZE));
    setStatus(nextStatus);
    setPage(nextPage);
  }, []);

  useEffect(() => {
    let alive = true;
    (async () => {
      try {
        const me = await sellerApi.profile();
        if (!alive) return;
        setProfile(me);
        const [memberList] = await Promise.all([sellerApi.members(), loadList(null, 0)]);
        if (!alive) return;
        setMembers(memberList);
      } catch (err) {
        if (!alive) return;
        // 403·422 는 사고가 아니라 이 계정의 상태다. 사용자가 해야 할 일이 서로 달라서
        // 문구도 나눈다 — 하나는 기다리거나 관리자에게 요청, 하나는 운영자만 고칠 수 있다.
        const code = apiErrorStatus(err);
        if (code === 403 || code === 422) {
          setBlocked(apiErrorMessage(err, '셀러 콘솔을 열 수 없습니다.'));
        } else {
          setError(apiErrorMessage(err, '셀러 정보를 불러오지 못했습니다.'));
        }
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => { alive = false; };
  }, [loadList]);

  const refresh = async (nextStatus = status, nextPage = page) => {
    try {
      await loadList(nextStatus, nextPage);
    } catch (err) {
      setError(apiErrorMessage(err, '신청서 목록을 불러오지 못했습니다.'));
    }
  };

  const startNew = () => { setEditing(null); setForm(EMPTY_FORM); setNotice(null); };

  const startEdit = async (submissionId: number) => {
    setError(null);
    try {
      const one = await sellerApi.submission(submissionId);
      setEditing(one);
      setForm({
        type: one.type,
        baseProductId: one.baseProductId,
        name: one.name,
        description: one.description,
        price: one.price,
        stock: one.stock,
        category: one.category,
        imageUrl: one.imageUrl,
        displayVisible: one.displayVisible,
      });
    } catch (err) {
      setError(apiErrorMessage(err, '신청서를 불러오지 못했습니다.'));
    }
  };

  const save = async () => {
    setSaving(true);
    setError(null);
    setNotice(null);
    const payload: SubmissionInput = {
      ...form,
      description: blankToNull(form.description ?? ''),
      category: blankToNull(form.category ?? ''),
      imageUrl: blankToNull(form.imageUrl ?? ''),
    };
    try {
      const saved = editing === null
        ? await sellerApi.createSubmission(payload)
        : await sellerApi.updateSubmission(editing.submissionId, payload);
      setEditing(saved);
      setNotice(`신청서 ${saved.submissionId} 를 저장했습니다. 아직 심사에 올라가지 않았습니다.`);
      await refresh();
    } catch (err) {
      setError(apiErrorMessage(err, '저장하지 못했습니다.'));
    } finally {
      setSaving(false);
    }
  };

  const submit = async () => {
    if (editing === null) return;
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const sent = await sellerApi.submitSubmission(editing.submissionId);
      setEditing(sent);
      setNotice(`신청서 ${sent.submissionId} 를 심사에 올렸습니다. 운영자 승인 후 몰에 등록됩니다.`);
      await refresh();
    } catch (err) {
      setError(apiErrorMessage(err, '심사 요청에 실패했습니다.'));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return <p className="p-4 text-sm text-gray-500" data-testid="seller-loading">불러오는 중…</p>;
  }

  if (blocked !== null) {
    return (
      <div className="mx-auto max-w-3xl p-4" data-testid="seller-blocked">
        <h1 className="text-xl font-semibold text-gray-900">셀러 콘솔</h1>
        <p className="mt-3 rounded bg-amber-50 p-4 text-sm text-amber-800">{blocked}</p>
        <p className="mt-2 text-xs text-gray-500">
          초대를 방금 받았다면 잠시 뒤 다시 열어 보세요. 계속 같은 화면이면 조직 관리자에게 문의하세요.
        </p>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-6xl space-y-4 p-4">
      <header className="flex flex-wrap items-center justify-between gap-2">
        <div>
          <h1 className="text-xl font-semibold text-gray-900" data-testid="seller-org">
            {profile?.organizationName ?? '셀러 콘솔'}
          </h1>
          {profile !== null && (
            <p className="text-sm text-gray-600" data-testid="seller-role">
              {MEMBER_ROLE_LABEL[profile.myRole]} · 구성원 {members.length}명
              {profile.sellerId === null && ' · 셀러 번호 확인 중'}
            </p>
          )}
        </div>
        <Link to="/seller/orders" className="text-sm text-blue-600 hover:underline">주문 · 출고 →</Link>
      </header>

      {notice !== null && (
        <p className="rounded bg-blue-50 p-3 text-sm text-blue-800" data-testid="seller-notice">{notice}</p>
      )}
      {error !== null && (
        <p className="rounded bg-red-50 p-3 text-sm text-red-700" data-testid="seller-error">{error}</p>
      )}

      <SubmissionForm
        value={form}
        onChange={setForm}
        onSave={() => void save()}
        onSubmit={editing !== null && editable(editing) ? () => void submit() : null}
        saving={saving}
        canSubmit={profile?.canSubmit ?? false}
        editing={editing}
      />

      <div className="flex flex-wrap items-center gap-2">
        {STATUS_FILTERS.map((filter) => (
          <button
            key={filter.label}
            type="button"
            onClick={() => void refresh(filter.value, 0)}
            data-testid={`filter-${filter.value ?? 'all'}`}
            className={`rounded px-3 py-1 text-sm ${status === filter.value
              ? 'bg-gray-900 text-white'
              : 'border border-gray-300 text-gray-700'}`}
          >
            {filter.label}
          </button>
        ))}
        <button type="button" onClick={startNew} data-testid="start-new"
                className="ml-auto rounded border border-gray-300 px-3 py-1 text-sm text-gray-700">
          새 신청서
        </button>
      </div>

      {result === null || result.content.length === 0
        ? <p className="text-sm text-gray-500" data-testid="submissions-empty">신청서가 없습니다.</p>
        : (
          <>
            <div className="overflow-x-auto rounded-lg bg-white shadow">
              <table className="min-w-full text-sm" data-testid="submissions-table">
                <thead className="bg-gray-50 text-left text-gray-600">
                  <tr>
                    <th className="px-3 py-2">번호</th>
                    <th className="px-3 py-2">상품명</th>
                    <th className="px-3 py-2 text-right">판매가</th>
                    <th className="px-3 py-2 text-right">재고</th>
                    <th className="px-3 py-2">상태</th>
                    <th className="px-3 py-2">상품번호</th>
                    <th className="px-3 py-2">비고</th>
                  </tr>
                </thead>
                <tbody>
                  {result.content.map((submission) => (
                    <tr key={submission.submissionId} className="border-t border-gray-100">
                      <td className="px-3 py-2">
                        <button type="button" onClick={() => void startEdit(submission.submissionId)}
                                data-testid={`submission-${submission.submissionId}`}
                                className="text-blue-600 hover:underline">
                          {submission.submissionId}
                        </button>
                      </td>
                      <td className="px-3 py-2">{submission.name}</td>
                      <td className="px-3 py-2 text-right">{formatMoney(submission.price)}</td>
                      <td className="px-3 py-2 text-right">{submission.stock.toLocaleString('ko-KR')}</td>
                      <td className="px-3 py-2"><StatusBadge submission={submission} /></td>
                      <td className="px-3 py-2">{submission.productId ?? '—'}</td>
                      {/* 반려 사유는 목록에서 바로 보인다 — 눌러 들어가야 보이면 고치지 않는다. */}
                      <td className="px-3 py-2 text-gray-600">
                        {submission.status === 'REJECTED' ? (submission.rejectReason ?? '사유 없음')
                          : submission.type === 'UPDATE' ? `상품 ${submission.baseProductId} 수정`
                            : ''}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            <div className="flex items-center justify-between text-sm text-gray-600">
              <span data-testid="submissions-total">
                전체 {result.totalElements.toLocaleString('ko-KR')}건 · {page + 1}/{Math.max(result.totalPages, 1)}쪽
              </span>
              <span className="space-x-2">
                <button type="button" disabled={page === 0} data-testid="submissions-prev"
                        onClick={() => void refresh(status, page - 1)}
                        className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40">이전</button>
                <button type="button" disabled={page + 1 >= result.totalPages} data-testid="submissions-next"
                        onClick={() => void refresh(status, page + 1)}
                        className="rounded border border-gray-300 px-2 py-1 disabled:opacity-40">다음</button>
              </span>
            </div>
          </>
        )}
    </div>
  );
}
