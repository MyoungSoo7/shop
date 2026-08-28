import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  partnerApi,
  formatMoney,
  canSeeSales,
  MEMBER_ROLE_LABEL,
  SELLER_TIER_LABEL,
  type PartnerProfile,
} from '@/api/partner';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const profile = (over: Partial<PartnerProfile> = {}): PartnerProfile => ({
  organizationId: 7,
  organizationName: '가나상사',
  orgType: 'SELLER',
  sellerId: 777,
  myRole: 'OWNER',
  salesAvailable: true,
  currentTier: 'VIP',
  tierEffectiveFrom: '2026-08-01',
  ...over,
});

describe('partnerApi', () => {
  beforeEach(() => vi.resetAllMocks());

  /**
   * 이 저장소가 참고한 레퍼런스 백오피스는 화면이 조직 번호를 파라미터로 실어 보냈다.
   * 그러면 번호만 바꿔 남의 회사 매출이 열린다. 여기서는 화면이 그 번호를 아예 모른다 —
   * 이 단언은 "안 보낸다" 를 코드로 고정한다.
   */
  it('어떤 호출도 조직 번호를 싣지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {}, headers: {} });

    await partnerApi.me();
    await partnerApi.members();
    await partnerApi.dashboard({ from: '2026-08-01', to: '2026-08-31' });
    await partnerApi.orders({ from: '2026-08-01' }, 0, 20);
    await partnerApi.order(10231);
    await partnerApi.exportOrders({ to: '2026-08-31' });

    for (const call of vi.mocked(api.get).mock.calls) {
      expect(String(call[0])).not.toMatch(/organization|orgId|sellerId/i);
    }
  });

  /** 서버에 쓰기 매핑이 없다. 클라이언트에도 없어야 원본이 둘로 갈리지 않는다. */
  it('쓰기 메서드가 없다', () => {
    const surface = Object.values(partnerApi);
    expect(surface).toHaveLength(6);
    expect(Object.keys(partnerApi).some((k) => /create|update|delete|save/i.test(k))).toBe(false);
  });

  /**
   * 게이트가 아니라 사고를 막는 단언이다. 입점 조직이 아닌 계정의 403 은 화면이 그리는
   * 상태라, 전역 인터셉터의 빨간 토스트가 겹치면 설명 위에 경고가 하나 더 뜬다.
   */
  it('me 만 전역 403 토스트를 끈다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: profile(), headers: {} });

    await partnerApi.me();
    await partnerApi.members();

    expect(api.get).toHaveBeenNthCalledWith(1, '/api/partner/me', { skipAuthToast: true });
    expect(api.get).toHaveBeenNthCalledWith(2, '/api/partner/members');
  });

  /**
   * {@code ?} 는 경로 리터럴에 있어야 한다. 보간이 경로에 붙으면 화면-API 대조 게이트가
   * 경로를 {@code /api/partner/dashboard*} 로 접어 어느 엔드포인트와도 맞추지 못하고,
   * 멀쩡히 불리는 컨트롤러가 "화면 없음" 부채로 집계된다.
   */
  it('쿼리는 ? 뒤에 붙고 빈 조건은 아예 실리지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {}, headers: {} });

    await partnerApi.dashboard({ from: '2026-08-01', to: null, orderId: null });
    await partnerApi.dashboard();

    expect(api.get).toHaveBeenNthCalledWith(1, '/api/partner/dashboard?from=2026-08-01');
    expect(api.get).toHaveBeenNthCalledWith(2, '/api/partner/dashboard?');
  });

  it('주문번호 0 도 조건이다 — falsy 라고 떨어뜨리면 0번 주문을 못 찾는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {}, headers: {} });

    await partnerApi.orders({ orderId: 0 }, 2, 50);

    expect(api.get).toHaveBeenCalledWith('/api/partner/orders?page=2&size=50&orderId=0');
  });

  it('CSV 는 헤더에서 파일명·건수·잘림 여부를 읽는다', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: new Blob(['a']),
      headers: {
        'content-disposition': "attachment; filename*=UTF-8''%EC%A3%BC%EB%AC%B8.csv",
        'x-partner-export-total': '1200',
        'x-partner-export-truncated': 'true',
      },
    });

    const result = await partnerApi.exportOrders({});

    expect(api.get).toHaveBeenCalledWith('/api/partner/exports/orders?', { responseType: 'blob' });
    expect(result.fileName).toBe('주문.csv');
    expect(result.totalMatched).toBe(1200);
    expect(result.truncated).toBe(true);
  });

  /**
   * 헤더가 없다고 "잘렸다" 로 겁주면 프록시가 헤더를 지우는 환경에서 매번 경고가 뜬다.
   * 그러면 진짜 잘린 날의 경고도 같이 무시된다.
   */
  it('헤더가 없으면 기본 파일명 · 잘리지 않음으로 읽는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: new Blob(['a']), headers: {} });

    const result = await partnerApi.exportOrders({});

    expect(result.fileName).toBe('partner_orders.csv');
    expect(result.totalMatched).toBe(0);
    expect(result.truncated).toBe(false);
  });

  it('단건 조회는 경로에 주문번호를 박는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {}, headers: {} });

    await partnerApi.order(10231);

    expect(api.get).toHaveBeenCalledWith('/api/partner/orders/10231');
  });
});

describe('표시 도우미', () => {
  /** 환불이 결제를 넘으면 실매출은 음수다. 0 으로 깎으면 화면과 정산이 어긋난다. */
  it('음수 금액을 깎지 않는다', () => {
    expect(formatMoney('-15000')).toBe('-15,000원');
    expect(formatMoney('0')).toBe('0원');
    expect(formatMoney('1234567.89')).toBe('1,234,567.89원');
  });

  /**
   * 화면이 orgType 으로 다시 판정하지 않는다 — 셀러 조직인데 셀러 ID 가 아직 안 온 상태가
   * 실제로 있다(조직 이벤트와 셀러 연결 이벤트의 도착 순서는 보장되지 않는다).
   */
  it('매출 노출 여부는 서버 판정만 따른다', () => {
    expect(canSeeSales(profile({ orgType: 'SELLER', sellerId: null, salesAvailable: false }))).toBe(false);
    expect(canSeeSales(profile({ orgType: 'CORPORATE', salesAvailable: true }))).toBe(true);
  });

  it('역할·등급 라벨이 모든 값에 있다', () => {
    expect(Object.keys(MEMBER_ROLE_LABEL)).toEqual(['OWNER', 'MANAGER', 'STAFF']);
    expect(Object.keys(SELLER_TIER_LABEL)).toEqual(['NORMAL', 'VIP', 'STRATEGIC']);
  });
});
