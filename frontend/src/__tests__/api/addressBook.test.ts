import { describe, it, expect, vi, beforeEach } from 'vitest';
import { addressBookApi, type AddressBook, type ShippingAddress } from '@/api/addressBook';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const address = (over: Partial<ShippingAddress> = {}): ShippingAddress => ({
  id: 1,
  label: '집',
  recipientName: '홍길동',
  phone: '010-1234-5678',
  postalCode: '06236',
  address1: '서울 강남구 테헤란로 1',
  address2: '301호',
  deliveryMemo: null,
  isDefault: true,
  createdAt: '2026-08-01T10:00:00',
  updatedAt: '2026-08-01T10:00:00',
  ...over,
});

const book: AddressBook = {
  addresses: [address(), address({ id: 2, label: '회사', isDefault: false })],
  totalCount: 2,
  maxAddresses: 30,
};

describe('addressBookApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('조회는 사용자별 경로를 쓴다 — 주소록은 소유자에 매인 자원이다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: book });

    const result = await addressBookApi.list(7);

    expect(api.get).toHaveBeenCalledWith('/users/7/shipping-addresses');
    expect(result.addresses).toHaveLength(2);
    expect(result.maxAddresses).toBe(30);
  });

  /** 별칭과 받는 분은 다른 칸이다. 레거시는 등록할 때 별칭 칸에 받는 사람 이름을 넣어 버렸다. */
  it('별칭과 받는 분이 각각 그대로 온다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: book });

    const result = await addressBookApi.list(7);

    expect(result.addresses[0].label).toBe('집');
    expect(result.addresses[0].recipientName).toBe('홍길동');
  });

  it('기본 배송지 단건 조회는 200 이면 그 줄을 준다', async () => {
    vi.mocked(api.get).mockResolvedValue({ status: 200, data: address() });

    expect(await addressBookApi.findDefault(7)).toMatchObject({ id: 1, isDefault: true });
    expect(api.get).toHaveBeenCalledWith('/users/7/shipping-addresses/default');
  });

  /**
   * 빈 주소록은 오류가 아니라 처음 오는 사람의 정상 상태다. 서버가 204 를 주고 화면은 null 을
   * 받는다 — 여기서 빈 문자열을 그대로 흘려보내면 화면이 {@code ''.label} 을 읽다 죽는다.
   */
  it('기본 배송지가 없으면 204 를 null 로 바꿔 준다', async () => {
    vi.mocked(api.get).mockResolvedValue({ status: 204, data: '' });

    expect(await addressBookApi.findDefault(7)).toBeNull();
  });

  it('등록은 POST 로 본문을 그대로 보낸다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: address() });

    await addressBookApi.register(7, {
      label: '집', recipientName: '홍길동', phone: '010-1234-5678', postalCode: '06236',
      address1: '서울 강남구 테헤란로 1', address2: '301호', deliveryMemo: null, makeDefault: true,
    });

    expect(api.post).toHaveBeenCalledWith('/users/7/shipping-addresses',
      expect.objectContaining({ label: '집', recipientName: '홍길동', makeDefault: true }));
  });

  it('수정은 배송지 id 를 경로에 담는다', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: address({ id: 2 }) });

    await addressBookApi.modify(7, 2, {
      label: '회사', recipientName: '김철수', phone: '010-0000-0000', postalCode: '06236',
      address1: '서울 강남구', address2: null, deliveryMemo: null, makeDefault: false,
    });

    expect(api.put).toHaveBeenCalledWith('/users/7/shipping-addresses/2',
      expect.objectContaining({ label: '회사' }));
  });

  /**
   * 기본 지정에 "내리기" 짝이 없다. 두 요청으로 나누면 사이에서 끊길 때 기본이 0개인 상태가
   * 남고, 그러면 주문서의 배송지 칸이 빈 채로 열린다.
   */
  it('기본 지정은 요청 한 번이고 응답은 주소록 전체다', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: book });

    const result = await addressBookApi.setDefault(7, 2);

    expect(api.put).toHaveBeenCalledTimes(1);
    expect(api.put).toHaveBeenCalledWith('/users/7/shipping-addresses/2/default');
    expect(result.addresses).toHaveLength(2);
  });

  /** 지운 것이 기본이면 남은 줄 하나가 올라온다 — 바뀐 줄만 주면 화면이 나머지를 추측해야 한다. */
  it('삭제 응답도 주소록 전체다', async () => {
    vi.mocked(api.delete).mockResolvedValue({
      data: { addresses: [address({ id: 2, label: '회사', isDefault: true })], totalCount: 1, maxAddresses: 30 },
    });

    const result = await addressBookApi.remove(7, 1);

    expect(api.delete).toHaveBeenCalledWith('/users/7/shipping-addresses/1');
    expect(result.addresses[0].isDefault).toBe(true);
  });
});
