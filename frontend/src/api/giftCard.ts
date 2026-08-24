import api from './axios';

/**
 * 기프트카드 원장 API.
 *
 * <p>백엔드 설계는 `docs/plan/gift-card-ledger.md`. 화면이 반드시 지켜야 할 것 두 가지 —
 *
 * <ul>
 *   <li><b>발행 응답의 code 는 다시 볼 수 없다.</b> 서버에는 해시만 남으므로 응답을 놓치면
 *       그 카드는 배포할 수 없다. 화면은 받은 즉시 내려받기·복사를 제공해야 한다.
 *   <li><b>등록 실패 사유를 추측해 보여 주지 않는다.</b> 서버가 사유를 구분하지 않는 이유는
 *       유효한 코드의 존재를 흘리지 않기 위함인데, 화면이 "이미 등록된 코드입니다" 같은 문구를
 *       지어내면 그 방어가 무의미해진다.
 * </ul>
 */

/** 발행 결과. `code` 는 이 응답에서만 존재한다. */
export interface IssuedGiftCard {
  giftCardId: number;
  code: string;
  codeLast4: string;
  faceAmount: number;
}

export interface ExpireGiftCardsResult {
  cardCount: number;
  forfeitedTotal: number;
  dryRun: boolean;
}

export interface RegisterGiftCardResult {
  giftCardId: number;
  codeLast4: string;
  faceAmount: number;
  /** 등록 후 그 사용자의 전체 상품권 잔액. */
  totalBalance: number;
}

export interface GiftCardBalance {
  userId: number;
  available: number;
}

export interface IssueGiftCardsRequest {
  quantity: number;
  faceAmount: number;
  validityDays: number;
  /** false 면 발행만 하고 등록은 막는다 — 유출된 코드가 곧 잔액이 되지 않게. */
  activate: boolean;
  memo?: string;
}

// 경로는 전체 리터럴로 적는다(point.ts 와 같은 이유).
export const giftCardApi = {
  issue: async (body: IssueGiftCardsRequest) =>
    (await api.post<IssuedGiftCard[]>('/admin/gift-cards/issue', body)).data,

  /** 기본은 미리보기다 — 호출부가 dryRun 을 빠뜨려도 실행되지 않는다. */
  runExpiry: async (dryRun = true, batchSize = 500) =>
    (await api.post<ExpireGiftCardsResult>('/admin/gift-cards/expiry/run', null, {
      params: { dryRun, batchSize },
    })).data,

  redeem: async (code: string) =>
    (await api.post<RegisterGiftCardResult>('/api/gift-cards/redeem', { code })).data,

  myBalance: async () => (await api.get<GiftCardBalance>('/api/gift-cards/me/balance')).data,
};
