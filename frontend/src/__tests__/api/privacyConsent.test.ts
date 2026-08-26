import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  adminPrivacyConsentApi,
  allRequiredAgreed,
  isStaleTermsError,
  privacyConsentApi,
  toAcceptances,
  type PrivacyConsentTerms,
} from '@/api/privacyConsent';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn() },
}));

const mocked = vi.mocked(api);

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
 * 주문 시점 동의 API 클라이언트 계약.
 *
 * <p>여기서 고정하는 것 넷 —
 *
 * <ul>
 *   <li><b>버전은 화면이 지어내지 않는다.</b> 요청에 실리는 termsVersion 은 서버가 내려준 그
 *       문안의 버전이어야 한다. 상수로 박으면 문장을 고친 뒤에도 옛 버전이 계속 나간다.
 *   <li><b>거절도 보낸다.</b> 체크하지 않은 항목이 요청에서 빠지면 "물었고 거절했다"가
 *       "묻지 않았다"와 구별되지 않는다.
 *   <li><b>선택 항목은 주문 버튼을 막지 않는다.</b> 막으면 선택을 필수처럼 받는 형태가 된다.
 *   <li><b>409 와 400 은 다른 실패다.</b> 409 는 "문안을 다시 받아라"라서 화면의 다음 동작이
 *       아예 다르다.
 * </ul>
 */
describe('allRequiredAgreed — 주문 버튼의 활성 조건', () => {
  it('필수를 다 체크하면 열린다', () => {
    const list = [terms(), terms({ code: 'COLLECTION_USE_ORDER' })];

    expect(allRequiredAgreed(list, { THIRD_PARTY_DELIVERY: true, COLLECTION_USE_ORDER: true })).toBe(true);
  });

  it('필수 하나라도 빠지면 닫힌다', () => {
    const list = [terms(), terms({ code: 'COLLECTION_USE_ORDER' })];

    expect(allRequiredAgreed(list, { THIRD_PARTY_DELIVERY: true })).toBe(false);
  });

  it('선택 항목을 거절해도 열린다 — 선택을 필수처럼 받지 않는다', () => {
    const list = [terms(), terms({ code: 'MARKETING_MESSAGE', required: false })];

    expect(allRequiredAgreed(list, { THIRD_PARTY_DELIVERY: true, MARKETING_MESSAGE: false })).toBe(true);
  });

  it('체크를 undefined 로 둔 것은 동의가 아니다 — 없는 키를 참으로 읽지 않는다', () => {
    expect(allRequiredAgreed([terms()], {})).toBe(false);
  });
});

describe('toAcceptances — 요청에 실리는 형태', () => {
  it('서버가 준 버전을 그대로 실어 보낸다', () => {
    expect(toAcceptances([terms({ version: 7 })], { THIRD_PARTY_DELIVERY: true })).toEqual([
      { termsCode: 'THIRD_PARTY_DELIVERY', termsVersion: 7, agreed: true },
    ]);
  });

  it('체크하지 않은 항목도 agreed=false 로 함께 나간다 — 거절도 기록이다', () => {
    const list = [terms(), terms({ code: 'MARKETING_MESSAGE', required: false })];

    expect(toAcceptances(list, { THIRD_PARTY_DELIVERY: true })).toEqual([
      { termsCode: 'THIRD_PARTY_DELIVERY', termsVersion: 2, agreed: true },
      { termsCode: 'MARKETING_MESSAGE', termsVersion: 2, agreed: false },
    ]);
  });

  it('문안이 없으면 빈 배열 — 없는 동의를 지어내지 않는다', () => {
    expect(toAcceptances([], { THIRD_PARTY_DELIVERY: true })).toEqual([]);
  });
});

describe('isStaleTermsError — 409 와 400 을 가른다', () => {
  it('409 는 문안이 낡은 것이다', () => {
    expect(isStaleTermsError({ response: { status: 409 } })).toBe(true);
  });

  it('400(필수 미동의)은 아니다 — 이때는 다시 받아 오는 게 아니라 체크를 안내해야 한다', () => {
    expect(isStaleTermsError({ response: { status: 400 } })).toBe(false);
  });

  it('응답 없는 실패(네트워크 끊김)에도 터지지 않는다', () => {
    expect(isStaleTermsError(new Error('Network Error'))).toBe(false);
    expect(isStaleTermsError(undefined)).toBe(false);
    expect(isStaleTermsError(null)).toBe(false);
  });
});

describe('privacyConsentApi — 고객 경로', () => {
  it('현행 문안은 /orders/consent-terms 에서 온다', async () => {
    mocked.get.mockResolvedValue({ data: [terms()] } as never);

    await expect(privacyConsentApi.terms()).resolves.toEqual([terms()]);
    expect(mocked.get).toHaveBeenCalledWith('/orders/consent-terms');
  });

  it('주문별 이력은 주문 번호가 경로에 들어간다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await privacyConsentApi.ofOrder(42);

    expect(mocked.get).toHaveBeenCalledWith('/orders/42/privacy-consents');
  });
});

describe('adminPrivacyConsentApi — 운영자 두 축', () => {
  it('사람으로 찾는 축', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await adminPrivacyConsentApi.ofUser(42);

    expect(mocked.get).toHaveBeenCalledWith('/admin/privacy-consents', {
      params: { userId: 42, limit: 100 },
    });
  });

  it('문안 버전으로 찾는 축 — 재동의 대상을 세는 질의라 사람 축과 합칠 수 없다', async () => {
    mocked.get.mockResolvedValue({ data: [] } as never);

    await adminPrivacyConsentApi.ofTermsVersion('THIRD_PARTY_DELIVERY', 1, 500);

    expect(mocked.get).toHaveBeenCalledWith('/admin/privacy-consents', {
      params: { termsCode: 'THIRD_PARTY_DELIVERY', termsVersion: 1, limit: 500 },
    });
  });
});
