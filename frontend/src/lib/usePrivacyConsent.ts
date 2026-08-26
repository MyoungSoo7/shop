import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ConsentAcceptance,
  PrivacyConsentTerms,
  allRequiredAgreed,
  privacyConsentApi,
  toAcceptances,
} from '@/api/privacyConsent';

/**
 * 결제 화면의 동의 상태.
 *
 * <p>컴포넌트 파일이 아니라 여기에 있는 이유는 react-refresh 규칙이다 — 컴포넌트 파일이
 * 컴포넌트 아닌 값을 내보내면 경고가 나고, 이 리포는 {@code --max-warnings 0} 이라 빌드가 깨진다
 * ({@code ShippingAddressForm} 과 {@code lib/shippingAddress} 가 갈라져 있는 것과 같은 이유).
 *
 * <p>훅이 문안을 <b>서버에서</b> 받아 오는 것이 핵심이다. 화면 상수로 박아 두면 문장을 고치는
 * 순간 이미 받아 둔 동의가 무엇에 대한 동의였는지 알 수 없게 된다.
 */
export interface PrivacyConsentState {
  terms: PrivacyConsentTerms[];
  /** 문안 코드 → 체크 여부. 없는 키는 "체크 안 함"이다. */
  agreed: Record<string, boolean>;
  toggle: (code: string, next: boolean) => void;
  /** 필수 항목이 전부 체크됐는가 — 주문 버튼의 활성 조건. */
  ready: boolean;
  /** 주문 요청에 그대로 실어 보낼 형태. 거절한 선택 항목도 함께 들어 있다. */
  acceptances: ConsentAcceptance[];
  loading: boolean;
  /** 문안을 못 받아 온 이유. 이때 {@link ready} 는 false 라 주문 버튼이 열리지 않는다. */
  error: string | null;
  /**
   * 문안을 다시 받는다 — 주문이 409(문안이 낡음)로 거절됐을 때 화면이 해야 할 일.
   *
   * <p>체크 상태는 <b>지운다</b>. 바뀐 문장에 대한 동의를 사용자가 다시 눌러야 하기 때문이다.
   * 옛 체크를 남겨 두면 읽지 않은 문장에 동의한 것이 되고, 그러면 이 기능이 없는 것과 같다.
   */
  reload: () => Promise<void>;
}

export function usePrivacyConsent(enabled = true): PrivacyConsentState {
  const [terms, setTerms] = useState<PrivacyConsentTerms[]>([]);
  const [agreed, setAgreed] = useState<Record<string, boolean>>({});
  const [loading, setLoading] = useState(enabled);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setTerms(await privacyConsentApi.terms());
      setAgreed({});
    } catch {
      // 문안을 못 받아 왔으면 동의를 받을 수 없다. 빈 목록으로 두면 ready 가 true 가 되어
      // (필수 항목이 0건이니까) 동의 없이 주문 버튼이 열린다 — 그 반대가 되도록 표시를 남긴다.
      setTerms([]);
      setError('동의 문안을 불러오지 못했습니다. 새로 고친 뒤 다시 시도해주세요.');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (enabled) void load();
  }, [enabled, load]);

  const toggle = useCallback((code: string, next: boolean) => {
    setAgreed((prev) => ({ ...prev, [code]: next }));
  }, []);

  const acceptances = useMemo(() => toAcceptances(terms, agreed), [terms, agreed]);
  const ready = !loading && !error && terms.length > 0 && allRequiredAgreed(terms, agreed);

  return { terms, agreed, toggle, ready, acceptances, loading, error, reload: load };
}
