import api from './axios';

/**
 * 배송지 주소록 — order-service {@code AddressBookController}.
 *
 * <p><b>왜 생겼나.</b> 지금까지 배송지는 주문서에 <b>매번 손으로 적는</b> 값이었다. 같은 집으로
 * 열 번을 시켜도 열 번 적었고, 오타 한 번이면 그 주문만 다른 데로 갔다. 주소록은 그 값을
 * 한 번 적어 두고 고르게 한다.
 *
 * <h3>기본 배송지는 "0개"가 될 수 없다</h3>
 * <p>레거시에서는 기본 배송지를 바꾸는 일이 <b>전부 내리기</b>와 <b>하나 올리기</b> 두 요청이었다.
 * 화면이 두 번 부르는 사이에 끊기면 기본이 하나도 없는 상태가 남고, 그러면 주문서의 배송지 칸이
 * 빈 채로 열린다. 그래서 이 API 에는 "내리기"가 없다 — {@link addressBookApi.setDefault} 하나만
 * 있고, 내리기·올리기는 서버 한 트랜잭션 안에서 끝난다.
 *
 * <h3>기본 지정·삭제의 응답은 주소록 전체다</h3>
 * <p>둘 다 <b>다른 줄</b>을 함께 바꾼다 — 기본을 옮기면 이전 기본이 내려가고, 기본을 지우면 남은
 * 것 중 하나가 올라온다. 바뀐 줄만 돌려주면 화면이 나머지를 추측해야 하므로 목록째로 준다.
 *
 * <h3>별칭과 받는 분은 다른 칸이다</h3>
 * <p>{@link ShippingAddress.label} 은 '집'·'회사' 같은 <b>내가 붙인 이름</b>이고,
 * {@link ShippingAddress.recipientName} 은 <b>실제로 받는 사람</b>이다. 레거시는 등록할 때 별칭 칸에
 * 받는 사람 이름을 넣고 수정할 때만 별칭을 넣어서, 등록하며 적은 별칭이 조용히 사라졌다.
 * 화면에서도 두 칸을 붙여 놓지 말 것 — 같은 값으로 보이면 사용자가 하나만 채운다.
 */

export interface ShippingAddress {
  id: number;
  /** 사용자가 붙인 별칭('집', '회사'). 받는 분 이름과 다른 값이다. */
  label: string;
  recipientName: string;
  phone: string;
  postalCode: string;
  address1: string;
  /** 상세주소. 없을 수 있다. */
  address2: string | null;
  /** 배송 메모. 없을 수 있다. */
  deliveryMemo: string | null;
  isDefault: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface AddressBook {
  /** 기본 배송지가 맨 위. 그 뒤는 최근 등록 순. */
  addresses: ShippingAddress[];
  totalCount: number;
  /** 보관 상한. 한도 근처에서 미리 알리기 위해 함께 온다. */
  maxAddresses: number;
}

/**
 * 등록·수정 본문.
 *
 * <p>{@link makeDefault} 가 false 라고 해서 기본이 내려가지는 않는다. 내리기만 하는 동작은
 * 기본을 0개로 만들 수 있어 서버가 제공하지 않는다.
 */
export interface AddressForm {
  label: string;
  recipientName: string;
  phone: string;
  postalCode: string;
  address1: string;
  address2?: string | null;
  deliveryMemo?: string | null;
  makeDefault: boolean;
}

const base = (userId: number) => `/users/${userId}/shipping-addresses`;

export const addressBookApi = {
  /** GET — 기본 배송지가 맨 위인 목록. */
  list: async (userId: number): Promise<AddressBook> => {
    const response = await api.get<AddressBook>(base(userId));
    return response.data;
  },

  /**
   * GET /default — 주문서 배송지 칸을 미리 채우는 용도.
   *
   * <p>주소록이 비어 있으면 서버가 <b>204</b> 를 준다. 빈 주소록은 오류가 아니라 처음 오는
   * 사람의 정상 상태라, 404 로 만들면 화면이 존재하지 않는 실패를 설명해야 한다. axios 는 204 의
   * 본문을 빈 문자열로 주므로 여기서 null 로 바꿔 넘긴다.
   */
  findDefault: async (userId: number): Promise<ShippingAddress | null> => {
    const response = await api.get<ShippingAddress | ''>(`${base(userId)}/default`);
    return response.status === 204 || !response.data ? null : (response.data as ShippingAddress);
  },

  /** POST — 첫 배송지는 makeDefault 를 보내지 않아도 기본이 된다. */
  register: async (userId: number, form: AddressForm): Promise<ShippingAddress> => {
    const response = await api.post<ShippingAddress>(base(userId), form);
    return response.data;
  },

  /** PUT — 내용 수정. makeDefault 가 true 면 기본 지정까지 함께 한다. */
  modify: async (
    userId: number,
    addressId: number,
    form: AddressForm,
  ): Promise<ShippingAddress> => {
    const response = await api.put<ShippingAddress>(`${base(userId)}/${addressId}`, form);
    return response.data;
  },

  /** PUT /default — 응답은 갱신된 주소록 전체다(이전 기본도 함께 바뀌므로). */
  setDefault: async (userId: number, addressId: number): Promise<AddressBook> => {
    const response = await api.put<AddressBook>(`${base(userId)}/${addressId}/default`);
    return response.data;
  },

  /** DELETE — 기본을 지우면 남은 것 중 하나가 승격한다. 응답은 그 결과가 반영된 주소록이다. */
  remove: async (userId: number, addressId: number): Promise<AddressBook> => {
    const response = await api.delete<AddressBook>(`${base(userId)}/${addressId}`);
    return response.data;
  },
};
