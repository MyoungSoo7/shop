import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  addressBookApi,
  type AddressBook,
  type AddressForm,
  type ShippingAddress,
} from '@/api/addressBook';
import { apiErrorMessage } from '@/lib/apiError';
import { useAuth } from '@/contexts/useAuth';

/**
 * 배송지 주소록.
 *
 * <p><b>기본 배송지를 해제하는 버튼은 두지 않는다.</b> 주소록에 줄이 있는 한 기본은 반드시 하나
 * 있어야 한다 — 0개가 되면 주문서의 배송지 칸이 빈 채로 열리고, 사용자는 왜 비었는지 알 수 없다.
 * 그래서 화면이 할 수 있는 일은 <b>다른 줄을 기본으로 올리는 것</b>뿐이고, 이전 기본이 내려가는 것은
 * 그 결과다. 내리기·올리기는 서버 한 트랜잭션 안에서 끝난다.
 *
 * <p><b>기본 지정·삭제 뒤에는 다시 조회하지 않고 응답을 그대로 쓴다.</b> 두 동작 모두 다른 줄을 함께
 * 바꾸므로 서버가 주소록 전체를 돌려준다. 다시 조회하면 그 사이 다른 탭의 변경이 섞여 방금 누른
 * 결과가 화면에서 뒤집혀 보인다.
 *
 * <p><b>별칭과 받는 분은 붙여 놓지 않는다.</b> 두 칸이 나란히 있으면 같은 값으로 읽혀 사용자가 하나만
 * 채운다. 레거시는 등록할 때 별칭 칸에 받는 사람 이름을 넣어 버려서 적어 둔 별칭이 사라졌다.
 */

const EMPTY_FORM: AddressForm = {
  label: '',
  recipientName: '',
  phone: '',
  postalCode: '',
  address1: '',
  address2: '',
  deliveryMemo: '',
  makeDefault: false,
};

const toForm = (address: ShippingAddress): AddressForm => ({
  label: address.label,
  recipientName: address.recipientName,
  phone: address.phone,
  postalCode: address.postalCode,
  address1: address.address1,
  address2: address.address2 ?? '',
  deliveryMemo: address.deliveryMemo ?? '',
  // 수정 화면에서 이 값을 켜 두면 안 된다. 저장할 때마다 기본을 다시 지정하게 되어,
  // 다른 줄을 기본으로 올려 둔 뒤 이 줄을 손보면 기본이 조용히 되돌아온다.
  makeDefault: false,
});

export default function AddressBookPage() {
  const { userId, loading: authLoading } = useAuth();

  const [book, setBook] = useState<AddressBook | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [form, setForm] = useState<AddressForm>(EMPTY_FORM);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [confirmingDeleteId, setConfirmingDeleteId] = useState<number | null>(null);

  const load = useCallback(async () => {
    if (userId === null) return;
    setError(null);
    try {
      setBook(await addressBookApi.list(userId));
    } catch (err) {
      setError(apiErrorMessage(err, '주소록을 불러오지 못했습니다.'));
    }
  }, [userId]);

  useEffect(() => { void load(); }, [load]);

  const resetForm = () => {
    setForm(EMPTY_FORM);
    setEditingId(null);
  };

  const submit = async () => {
    if (userId === null || busy) return;
    setBusy(true);
    setError(null);
    try {
      if (editingId === null) {
        await addressBookApi.register(userId, form);
      } else {
        await addressBookApi.modify(userId, editingId, form);
      }
      resetForm();
      // 등록·수정은 한 줄만 돌려주는데 기본 지정이 함께 일어났을 수 있다. 그 경우 다른 줄도
      // 바뀌므로 목록을 다시 읽는다 — 기본 지정·삭제와 달리 여기서는 전체 응답이 없다.
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, editingId === null
        ? '배송지를 등록하지 못했습니다.'
        : '배송지를 수정하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const setDefault = async (addressId: number) => {
    if (userId === null || busy) return;
    setBusy(true);
    setError(null);
    try {
      setBook(await addressBookApi.setDefault(userId, addressId));
    } catch (err) {
      setError(apiErrorMessage(err, '기본 배송지를 바꾸지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const remove = async (addressId: number) => {
    if (userId === null || busy) return;
    setBusy(true);
    setError(null);
    try {
      setBook(await addressBookApi.remove(userId, addressId));
      setConfirmingDeleteId(null);
      if (editingId === addressId) resetForm();
    } catch (err) {
      setError(apiErrorMessage(err, '배송지를 지우지 못했습니다.'));
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
        <p>주소록을 보려면 <Link to="/login" className="text-blue-600 underline">로그인</Link>이 필요합니다.</p>
      </main>
    );
  }

  const full = book !== null && book.totalCount >= book.maxAddresses;
  const canSubmit = form.label.trim() !== ''
    && form.recipientName.trim() !== ''
    && form.phone.trim() !== ''
    && form.postalCode.trim() !== ''
    && form.address1.trim() !== '';

  const field = (
    key: 'label' | 'recipientName' | 'phone' | 'postalCode' | 'address1' | 'address2' | 'deliveryMemo',
    label: string,
    hint?: string,
  ) => (
    <label className="block text-sm">
      <span className="text-gray-700">{label}</span>
      {hint && <span className="ml-1 text-xs text-gray-500">{hint}</span>}
      <input
        type="text"
        value={form[key] ?? ''}
        onChange={e => setForm({ ...form, [key]: e.target.value })}
        data-testid={`address-${key}`}
        className="mt-1 w-full rounded border px-2 py-1.5"
      />
    </label>
  );

  return (
    <main className="mx-auto max-w-3xl p-6 space-y-6">
      <header>
        <h1 className="text-2xl font-bold">배송지 주소록</h1>
        <p className="text-sm text-gray-500">
          자주 쓰는 배송지를 저장해 두고 주문할 때 고릅니다. 기본 배송지는 항상 하나 있습니다.
        </p>
      </header>

      {error && <p role="alert" className="text-red-600">{error}</p>}

      {book && (
        <p className="text-sm text-gray-600" data-testid="address-count">
          {book.totalCount}개 / 최대 {book.maxAddresses}개
        </p>
      )}

      <section className="rounded border p-4 space-y-3">
        <h2 className="font-semibold">
          {editingId === null ? '새 배송지' : '배송지 수정'}
        </h2>

        <div className="grid gap-3 sm:grid-cols-2">
          {/* 별칭과 받는 분을 나란히 두되 설명을 각각 붙인다 — 같은 값으로 읽히면 하나만 채운다. */}
          {field('label', '별칭', '집 · 회사처럼 내가 알아볼 이름')}
          {field('recipientName', '받는 분', '실제로 물건을 받는 사람')}
          {field('phone', '연락처')}
          {field('postalCode', '우편번호')}
        </div>
        {field('address1', '주소')}
        {field('address2', '상세주소', '선택')}
        {field('deliveryMemo', '배송 메모', '선택')}

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={form.makeDefault}
            onChange={e => setForm({ ...form, makeDefault: e.target.checked })}
            data-testid="address-makeDefault"
          />
          {/* 끄는 것으로 기본을 내릴 수는 없다. 내리기만 하는 동작은 기본을 0개로 만든다. */}
          <span>기본 배송지로 지정 (끄더라도 지금 기본이 내려가지는 않습니다)</span>
        </label>

        {book?.totalCount === 0 && (
          <p className="text-sm text-gray-600">첫 배송지는 지정하지 않아도 기본이 됩니다.</p>
        )}
        {editingId === null && full && (
          <p className="text-sm text-amber-700" data-testid="address-full">
            보관 한도({book?.maxAddresses}개)가 찼습니다. 쓰지 않는 배송지를 지운 뒤 등록하세요.
          </p>
        )}

        <div className="flex gap-2">
          <button
            type="button"
            onClick={() => void submit()}
            disabled={busy || !canSubmit || (editingId === null && full)}
            className="rounded bg-blue-600 px-3 py-1.5 text-white disabled:opacity-50"
          >
            {editingId === null ? '등록' : '저장'}
          </button>
          {editingId !== null && (
            <button type="button" onClick={resetForm} disabled={busy}
              className="rounded border px-3 py-1.5">
              취소
            </button>
          )}
        </div>
      </section>

      {book && book.addresses.length === 0 && (
        <p className="text-gray-500" data-testid="addressbook-empty">
          아직 저장한 배송지가 없습니다.
        </p>
      )}

      <ul className="space-y-3">
        {book?.addresses.map(address => (
          <li key={address.id}
            data-testid={`address-item-${address.id}`}
            className="rounded border p-3 space-y-2">
            <div className="flex items-center gap-2">
              <span className="font-medium">{address.label}</span>
              {address.isDefault && (
                <span data-testid={`address-default-${address.id}`}
                  className="rounded bg-blue-100 px-2 py-0.5 text-xs text-blue-800">
                  기본 배송지
                </span>
              )}
            </div>
            <p className="text-sm text-gray-700">
              {address.recipientName} · {address.phone}
            </p>
            <p className="text-sm text-gray-600">
              ({address.postalCode}) {address.address1}
              {address.address2 && ` ${address.address2}`}
            </p>
            {address.deliveryMemo && (
              <p className="text-sm text-gray-500">메모: {address.deliveryMemo}</p>
            )}

            <div className="flex flex-wrap gap-2">
              {/* 이미 기본인 줄에는 버튼을 두지 않는다 — 누를 수 있으면 "해제"로 읽힌다. */}
              {!address.isDefault && (
                <button type="button" onClick={() => void setDefault(address.id)} disabled={busy}
                  aria-label={`${address.label} 기본 배송지로 지정`}
                  className="rounded border px-3 py-1.5 text-sm disabled:opacity-50">
                  기본으로
                </button>
              )}
              <button type="button"
                onClick={() => { setEditingId(address.id); setForm(toForm(address)); }}
                disabled={busy}
                aria-label={`${address.label} 수정`}
                className="rounded border px-3 py-1.5 text-sm disabled:opacity-50">
                수정
              </button>
              {confirmingDeleteId === address.id ? (
                <span className="flex items-center gap-2" data-testid={`delete-confirm-${address.id}`}>
                  {/* 기본을 지우면 남은 것 중 하나가 올라온다. 그 사실을 지우기 전에 말한다. */}
                  <span className="text-sm">
                    {address.isDefault && book.totalCount > 1
                      ? '지우면 다른 배송지가 기본이 됩니다. 지울까요?'
                      : '되돌릴 수 없습니다. 지울까요?'}
                  </span>
                  <button type="button" onClick={() => void remove(address.id)} disabled={busy}
                    className="rounded bg-red-600 px-3 py-1.5 text-sm text-white disabled:opacity-50">
                    지우기
                  </button>
                  <button type="button" onClick={() => setConfirmingDeleteId(null)} disabled={busy}
                    className="rounded border px-3 py-1.5 text-sm">
                    취소
                  </button>
                </span>
              ) : (
                <button type="button" onClick={() => setConfirmingDeleteId(address.id)} disabled={busy}
                  aria-label={`${address.label} 삭제`}
                  className="rounded border px-3 py-1.5 text-sm disabled:opacity-50">
                  삭제
                </button>
              )}
            </div>
          </li>
        ))}
      </ul>
    </main>
  );
}
