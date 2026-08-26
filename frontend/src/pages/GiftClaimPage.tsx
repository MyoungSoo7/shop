import React, { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router-dom';
import {
  GIFT_CLAIM_STATUS_LABEL,
  giftClaimApi,
  type GiftAddressPayload,
  type GiftViewResponse,
} from '@/api/gift';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 선물 받기 — <b>로그인하지 않은 사람</b>이 보는 화면.
 *
 * <p>이 페이지가 왜 로그인 밖에 있어야 하는가. 받는 사람은 이 가게와 아무 관계도 없는 사람이다.
 * 여기서 회원가입을 요구하면 "주소를 주기 싫어서" 선물을 못 받던 문제가 "가입하기 싫어서"로
 * 이름만 바뀐 채 그대로 남는다. 그래서 라우트는 {@code ProtectedRoute} 밖에 두고, 서버도 이 경로만
 * ({@code /gift-claims/**}) 열어 둔다.
 *
 * <p><b>세 단계로 나눈 이유.</b> 링크 하나로 바로 주소를 받게 하면 링크가 곧 권한이 된다 — 메신저에
 * 잘못 붙여넣은 링크를 주운 사람이 남의 선물을 자기 집으로 돌릴 수 있다. 그래서 링크는 "무엇이
 * 왔는지 보는 것"까지만 열고, 주소를 내려면 선물에 적힌 번호로 간 6자리를 한 번 더 통과해야 한다.
 *
 * <p>화면에 <b>금액이 없다.</b> 서버 응답에 아예 담기지 않는다 — 받는 사람에게 선물 가격을 보여 줄
 * 이유가 없고, 인가가 토큰 하나에 걸려 있으니 나가는 값도 최소한이어야 한다.
 */

type Step = 'view' | 'code' | 'address' | 'done';

const emptyAddress: GiftAddressPayload = {
  recipientName: '',
  phone: '',
  postalCode: '',
  address1: '',
  address2: '',
  deliveryMemo: '',
};

const GiftClaimPage: React.FC = () => {
  const { token = '' } = useParams<{ token: string }>();

  const [gift, setGift] = useState<GiftViewResponse | null>(null);
  const [step, setStep] = useState<Step>('view');
  const [code, setCode] = useState('');
  const [address, setAddress] = useState<GiftAddressPayload>(emptyAddress);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const view = await giftClaimApi.view(token);
      setGift(view);
      // 이미 주소를 낸 링크로 다시 들어온 경우 — 입력 폼을 다시 보여 주면 두 번 낼 수 있는 것처럼
      // 보인다. 서버는 거절하지만 화면이 먼저 정직해야 한다.
      if (view.status === 'CLAIMED') setStep('done');
      else if (view.status === 'VERIFIED') setStep('address');
      else setStep('view');
    } catch (err) {
      setError(apiErrorMessage(err, '선물을 찾을 수 없습니다. 링크를 다시 확인해 주세요.'));
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => {
    if (!token) {
      setError('유효하지 않은 링크입니다.');
      setLoading(false);
      return;
    }
    void load();
  }, [token, load]);

  const requestCode = async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await giftClaimApi.requestCode(token);
      setNotice(`${gift?.maskedPhone ?? '등록된 번호'} 로 인증번호를 보냈습니다.`);
      setStep('code');
    } catch (err) {
      setError(apiErrorMessage(err, '인증번호를 보내지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const verify = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await giftClaimApi.verify(token, code);
      setNotice(null);
      setStep('address');
    } catch (err) {
      // 남은 시도 횟수가 서버 메시지에 담겨 온다. 그대로 보여 준다 — 몇 번째에 잠기는지 모르면
      // 문의로 온다.
      setError(apiErrorMessage(err, '인증번호가 맞지 않습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const submitAddress = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await giftClaimApi.submitAddress(token, address);
      setStep('done');
      await load();
    } catch (err) {
      setError(apiErrorMessage(err, '배송지를 저장하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  if (loading) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50">
        <p className="text-sm text-gray-500">불러오는 중…</p>
      </div>
    );
  }

  if (!gift) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50 px-4">
        <div className="max-w-md w-full rounded-md bg-red-50 p-4">
          <p className="text-sm text-red-800">{error ?? '유효하지 않은 링크입니다.'}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-gray-50 py-12 px-4 sm:px-6 lg:px-8">
      <div className="max-w-md mx-auto space-y-6">
        <section className="bg-white rounded-lg shadow p-6 space-y-4">
          <header>
            <p className="text-sm text-gray-500">선물이 도착했어요</p>
            <h1 className="text-xl font-semibold text-gray-900">
              {gift.recipientName} 님께
            </h1>
          </header>

          {gift.message && (
            <blockquote className="border-l-4 border-indigo-200 pl-3 text-sm text-gray-700 italic">
              {gift.message}
            </blockquote>
          )}

          <ul className="divide-y divide-gray-100 text-sm">
            {gift.items.map((item, index) => (
              <li key={`${item.productName}-${index}`} className="py-2 flex justify-between">
                <span className="text-gray-800">{item.productName}</span>
                <span className="text-gray-500">{item.quantity}개</span>
              </li>
            ))}
          </ul>

          <p className="text-xs text-gray-400">
            {new Date(gift.expiresAt).toLocaleDateString('ko-KR')} 까지 배송지를 입력해 주세요
          </p>
        </section>

        {error && (
          <div role="alert" className="rounded-md bg-red-50 p-3 text-sm text-red-800">{error}</div>
        )}
        {notice && (
          <div role="status" className="rounded-md bg-blue-50 p-3 text-sm text-blue-800">{notice}</div>
        )}

        {!gift.actionable && step !== 'done' && (
          <div className="rounded-md bg-gray-100 p-4 text-sm text-gray-700">
            이 선물은 더 이상 받을 수 없습니다 ({GIFT_CLAIM_STATUS_LABEL[gift.status]}).
            보낸 분에게 다시 보내 달라고 알려 주세요.
          </div>
        )}

        {gift.actionable && step === 'view' && (
          <section className="bg-white rounded-lg shadow p-6 space-y-3">
            <p className="text-sm text-gray-700">
              배송지를 입력하려면 본인확인이 필요합니다. 인증번호를{' '}
              <strong>{gift.maskedPhone}</strong> 로 보냅니다.
            </p>
            <button
              type="button"
              onClick={requestCode}
              disabled={busy}
              className="w-full rounded-md bg-indigo-600 py-2 text-white text-sm font-medium disabled:opacity-50"
            >
              인증번호 받기
            </button>
          </section>
        )}

        {gift.actionable && step === 'code' && (
          <form onSubmit={verify} className="bg-white rounded-lg shadow p-6 space-y-3">
            <label htmlFor="gift-code" className="block text-sm font-medium text-gray-700">
              인증번호 6자리
            </label>
            <input
              id="gift-code"
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, '').slice(0, 6))}
              inputMode="numeric"
              autoComplete="one-time-code"
              placeholder="000000"
              className="w-full rounded-md border border-gray-300 px-3 py-2 tracking-widest"
            />
            <button
              type="submit"
              disabled={busy || code.length !== 6}
              className="w-full rounded-md bg-indigo-600 py-2 text-white text-sm font-medium disabled:opacity-50"
            >
              확인
            </button>
            <button
              type="button"
              onClick={requestCode}
              disabled={busy}
              className="w-full text-sm text-gray-500 underline disabled:opacity-50"
            >
              인증번호 다시 받기
            </button>
          </form>
        )}

        {gift.actionable && step === 'address' && (
          <form onSubmit={submitAddress} className="bg-white rounded-lg shadow p-6 space-y-3">
            <h2 className="text-sm font-medium text-gray-900">받을 주소</h2>
            {/* 이름은 선택이다 — 화면에 이미 자기 이름이 적혀 있고, 비우면 그 이름을 쓴다. */}
            <input
              aria-label="받는 분 이름 (비우면 선물에 적힌 이름)"
              placeholder={`받는 분 이름 (비우면 ${gift.recipientName})`}
              value={address.recipientName ?? ''}
              onChange={(e) => setAddress({ ...address, recipientName: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            />
            <input
              aria-label="연락처"
              placeholder="연락처"
              required
              value={address.phone}
              onChange={(e) => setAddress({ ...address, phone: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            />
            <input
              aria-label="우편번호"
              placeholder="우편번호"
              required
              value={address.postalCode}
              onChange={(e) => setAddress({ ...address, postalCode: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            />
            <input
              aria-label="주소"
              placeholder="주소"
              required
              value={address.address1}
              onChange={(e) => setAddress({ ...address, address1: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            />
            <input
              aria-label="상세주소"
              placeholder="상세주소"
              value={address.address2 ?? ''}
              onChange={(e) => setAddress({ ...address, address2: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            />
            <input
              aria-label="배송 메모"
              placeholder="배송 메모 (선택)"
              value={address.deliveryMemo ?? ''}
              onChange={(e) => setAddress({ ...address, deliveryMemo: e.target.value })}
              className="w-full rounded-md border border-gray-300 px-3 py-2 text-sm"
            />
            <button
              type="submit"
              disabled={busy}
              className="w-full rounded-md bg-indigo-600 py-2 text-white text-sm font-medium disabled:opacity-50"
            >
              이 주소로 받기
            </button>
          </form>
        )}

        {step === 'done' && (
          <div className="rounded-md bg-green-50 p-4 text-sm text-green-800">
            배송지가 전달됐습니다. 곧 출발해요. 보낸 분에게는 주소가 공개되지 않습니다.
          </div>
        )}
      </div>
    </div>
  );
};

export default GiftClaimPage;
