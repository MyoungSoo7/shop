import api from './axios';

/**
 * 찜(위시리스트) — order-service {@code WishlistController}.
 *
 * <p><b>왜 생겼나.</b> 지금까지 "나중에 살 것"을 담아 둘 곳은 장바구니뿐이었다. 그래서 실무에서는
 * 장바구니가 두 가지 목록을 겸했다 — <b>지금 결제할 것</b>과 <b>언젠가 살 것</b>. 이 둘이 섞이면
 * 결제 화면에서 매번 체크박스를 골라내야 하고, 실수로 전체선택을 누르면 살 생각이 없던 물건까지
 * 결제된다. 반대로 정리하려고 지우면 "언젠가 살 것"이 사라진다.
 *
 * <h3>없어진 것을 감추지 않는다</h3>
 * <p>찜은 성격상 <b>오래 담겨 있다</b>. 그 사이 상품은 품절되고 단종되고 삭제된다. 이때 목록에서
 * 조용히 빼면 사용자는 자기가 뭘 담았는지 영영 알 수 없고, 그대로 두면 눌렀을 때 404 를 만난다.
 * 그래서 서버가 <b>거르지 않고</b> {@link WishlistItem.reason} 에 사유를 실어 준다. 화면은 항목을
 * 지우는 대신 사유를 보여 준다.
 *
 * <h3>품절과 단종은 다르다</h3>
 * <p>{@link WishlistItem.gone} 은 <b>단종·삭제</b>만 참이다. 품절은 거짓이다 — 재입고를 기다리는 것이
 * 찜의 가장 큰 용도라, 품절을 일괄 정리에 넣으면 사용자가 제일 지키고 싶어 한 줄을 버튼 한 번이
 * 지운다. 이 구분은 서버 {@code WishlistAvailability#isGone} 에 있고 화면은 그 판정을 그대로 쓴다.
 *
 * <h3>담기·빼기는 멱등이고, 응답은 결과 상태다</h3>
 * <p>PUT/DELETE 는 이미 그런 상태여도 200 이다. 하트는 연타되는 버튼이라 "이미 담김"을 오류로
 * 만들면 화면이 존재하지 않는 실패를 사용자에게 설명해야 한다. 응답의 {@link Mutation.wished} 는
 * <b>방금 한 동작이 아니라 끝난 뒤의 상태</b>라, 화면은 자기가 뭘 눌렀는지 기억하지 않고 이 값만
 * 그리면 된다. {@link Mutation.changed} 는 토스트를 띄울지 정하는 용도다.
 */

/** 서버 {@code WishlistAvailability} 와 같은 집합. */
export type WishlistAvailabilityValue =
  | 'AVAILABLE'
  | 'OUT_OF_STOCK'
  | 'NOT_SELLING'
  | 'DISCONTINUED'
  | 'REMOVED';

/**
 * 일괄 정리 대상 — <b>단종·삭제만</b>.
 *
 * <p>서버가 항목마다 {@code gone} 을 이미 계산해 주므로 화면은 보통 이 집합을 직접 쓸 일이 없다.
 * 확인 문구처럼 항목이 손에 없는 자리에서만 쓴다.
 */
export const GONE_AVAILABILITIES: readonly WishlistAvailabilityValue[] = ['DISCONTINUED', 'REMOVED'];

export interface WishlistItem {
  productId: number;
  name: string;
  /** 삭제된 상품은 값을 매길 수 없어 null 이다. 화면은 0원으로 그리면 안 된다. */
  price: number | null;
  primaryImageUrl: string | null;
  availability: WishlistAvailabilityValue;
  /** 사용자에게 보여 줄 한글 사유. 화면이 enum 을 다시 번역하지 않도록 서버가 함께 준다. */
  reason: string;
  available: boolean;
  /** 되살아나지 않는가 — 품절은 false 다. */
  gone: boolean;
  addedAt: string;
}

export interface Wishlist {
  items: WishlistItem[];
  totalCount: number;
  /** 일괄 정리로 사라질 개수. 버튼을 띄울지 판단하는 근거. */
  goneCount: number;
  /** 보관 상한. 한도 근처에서 미리 알리기 위해 함께 온다. */
  maxItems: number;
}

export interface Mutation {
  /** 호출이 끝난 뒤 담겨 있는가. 하트를 칠할 근거는 이 값 하나다. */
  wished: boolean;
  /** 이번 호출로 실제로 바뀌었는가. 토스트를 띄울지 정한다. */
  changed: boolean;
  count: number;
}

export interface ContainsResult {
  productId: number;
  wished: boolean;
}

export interface PurgeResult {
  /** 무엇이 지워졌는지. 개수만 주면 화면이 "3개 정리됨" 밖에 말할 수 없다. */
  removed: WishlistItem[];
  wishlist: Wishlist;
}

const base = (userId: number) => `/users/${userId}/wishlist`;

export const wishlistApi = {
  /** GET — 품절·단종·삭제 항목도 사유와 함께 전부 온다. */
  list: async (userId: number): Promise<Wishlist> => {
    const response = await api.get<Wishlist>(base(userId));
    return response.data;
  },

  /** PUT — 멱등. 이미 담겨 있어도 200 이고 {@code changed:false} 로 온다. */
  add: async (userId: number, productId: number): Promise<Mutation> => {
    const response = await api.put<Mutation>(`${base(userId)}/products/${productId}`);
    return response.data;
  },

  /** DELETE — 멱등. 담겨 있지 않아도 200 이다(사용자가 원한 결과가 이미 성립해 있다). */
  remove: async (userId: number, productId: number): Promise<Mutation> => {
    const response = await api.delete<Mutation>(`${base(userId)}/products/${productId}`);
    return response.data;
  },

  /**
   * GET 단건 — 상품 화면의 하트 표시용.
   *
   * <p>목록 전체를 받아 와 찾지 않는다. 찜은 최대 수백 개라 상품 한 개의 하트를 그리려고
   * 그 전부와 상품 정보까지 끌어오게 된다.
   */
  contains: async (userId: number, productId: number): Promise<ContainsResult> => {
    const response = await api.get<ContainsResult>(`${base(userId)}/products/${productId}`);
    return response.data;
  },

  /** DELETE /gone — 단종·삭제만 지운다. 품절은 남는다. */
  purgeGone: async (userId: number): Promise<PurgeResult> => {
    const response = await api.delete<PurgeResult>(`${base(userId)}/gone`);
    return response.data;
  },
};
