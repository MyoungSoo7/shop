import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { usePrivacyConsent } from '@/lib/usePrivacyConsent';
import { privacyConsentApi, type PrivacyConsentTerms } from '@/api/privacyConsent';

// 서버 호출만 가짜로 바꾼다. ready 판정과 요청 변환은 진짜 함수를 쓴다 — 그것까지 가짜면
// 훅이 실제로 그 조건을 붙였는지 검사하지 못한다.
vi.mock('@/api/privacyConsent', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/privacyConsent')>()),
  privacyConsentApi: { terms: vi.fn(), ofOrder: vi.fn() },
}));

const mockedTerms = vi.mocked(privacyConsentApi.terms);

beforeEach(() => vi.clearAllMocks());

const terms = (over: Partial<PrivacyConsentTerms> = {}): PrivacyConsentTerms => ({
  code: 'THIRD_PARTY_DELIVERY',
  version: 2,
  consentType: 'THIRD_PARTY_PROVISION',
  title: '배송을 위한 개인정보 제3자 제공 동의',
  recipient: '배송업체',
  purpose: '주문 상품의 배송',
  providedItems: '받는 분 이름, 휴대전화번호, 주소',
  retention: '배송 완료 후 90일',
  body: '전문입니다',
  required: true,
  effectiveFrom: '2026-07-28T00:00:00',
  ...over,
});

/**
 * 결제 화면의 동의 상태.
 *
 * <p>이 훅에서 틀리면 조용히 사고가 되는 자리가 둘이다.
 *
 * <p>하나는 <b>문안을 못 받아 왔을 때</b>다. 빈 목록으로 두면 "필수 항목이 0건"이라 ready 가
 * 참이 되고, 동의 없이 주문 버튼이 열린다. 실패는 반드시 닫힌 상태여야 한다.
 *
 * <p>다른 하나는 <b>다시 받아 올 때 체크를 남기는 것</b>이다. 409 는 문장이 바뀌었다는 뜻이므로,
 * 옛 체크를 남기면 사용자가 읽지 않은 문장에 동의한 것이 된다 — 그러면 이 기능이 없는 것과 같다.
 */
describe('usePrivacyConsent — 결제 화면의 동의 상태', () => {
  it('문안을 받아 오고, 필수를 체크해야 ready 가 된다', async () => {
    mockedTerms.mockResolvedValue([terms(), terms({ code: 'MARKETING_MESSAGE', required: false })]);

    const { result } = renderHook(() => usePrivacyConsent());
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.terms).toHaveLength(2);
    expect(result.current.ready).toBe(false);

    act(() => result.current.toggle('THIRD_PARTY_DELIVERY', true));
    expect(result.current.ready).toBe(true);
  });

  it('선택 항목을 거절해도 ready 는 유지된다', async () => {
    mockedTerms.mockResolvedValue([terms(), terms({ code: 'MARKETING_MESSAGE', required: false })]);

    const { result } = renderHook(() => usePrivacyConsent());
    await waitFor(() => expect(result.current.loading).toBe(false));

    act(() => result.current.toggle('THIRD_PARTY_DELIVERY', true));
    act(() => result.current.toggle('MARKETING_MESSAGE', false));

    expect(result.current.ready).toBe(true);
    expect(result.current.acceptances).toEqual([
      { termsCode: 'THIRD_PARTY_DELIVERY', termsVersion: 2, agreed: true },
      { termsCode: 'MARKETING_MESSAGE', termsVersion: 2, agreed: false },
    ]);
  });

  it('문안을 못 받아 오면 ready 는 거짓이다 — 빈 목록이 "필수 0건"으로 읽히면 안 된다', async () => {
    mockedTerms.mockRejectedValue(new Error('boom'));

    const { result } = renderHook(() => usePrivacyConsent());
    await waitFor(() => expect(result.current.loading).toBe(false));

    expect(result.current.terms).toEqual([]);
    expect(result.current.error).toBeTruthy();
    expect(result.current.ready).toBe(false);
  });

  it('enabled=false 면 부르지 않고 로딩도 아니다 — 동의를 안 받는 화면이 헛요청하지 않도록', () => {
    const { result } = renderHook(() => usePrivacyConsent(false));

    expect(mockedTerms).not.toHaveBeenCalled();
    expect(result.current.loading).toBe(false);
    expect(result.current.ready).toBe(false);
  });

  it('reload 는 체크를 지운다 — 바뀐 문장에는 다시 동의를 받아야 한다', async () => {
    mockedTerms.mockResolvedValue([terms({ version: 2 })]);

    const { result } = renderHook(() => usePrivacyConsent());
    await waitFor(() => expect(result.current.loading).toBe(false));
    act(() => result.current.toggle('THIRD_PARTY_DELIVERY', true));
    expect(result.current.ready).toBe(true);

    mockedTerms.mockResolvedValue([terms({ version: 3 })]);
    await act(async () => {
      await result.current.reload();
    });

    expect(result.current.terms[0].version).toBe(3);
    expect(result.current.agreed).toEqual({});
    expect(result.current.ready).toBe(false);
  });

  it('reload 가 실패하면 앞선 오류 표시가 남는다 — 조용히 성공한 척하지 않는다', async () => {
    mockedTerms.mockResolvedValue([terms()]);

    const { result } = renderHook(() => usePrivacyConsent());
    await waitFor(() => expect(result.current.loading).toBe(false));

    mockedTerms.mockRejectedValue(new Error('boom'));
    await act(async () => {
      await result.current.reload();
    });

    expect(result.current.error).toBeTruthy();
    expect(result.current.ready).toBe(false);
  });
});
