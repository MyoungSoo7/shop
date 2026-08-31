import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  sellerApi,
  formatMoney,
  displayStatus,
  MEMBER_ROLE_LABEL,
  SUBMISSION_STATUS_LABEL,
  type Submission,
} from '@/api/seller';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: { get: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() },
}));

const submission = (over: Partial<Submission> = {}): Submission => ({
  submissionId: 11,
  sellerId: 777,
  type: 'NEW',
  baseProductId: null,
  name: '사과 1kg',
  description: null,
  price: 12000,
  stock: 30,
  category: null,
  imageUrl: null,
  displayVisible: true,
  status: 'DRAFT',
  rejectReason: null,
  productId: null,
  awaitingCatalog: false,
  createdByUserId: 3,
  decidedByUserId: null,
  submittedAt: null,
  decidedAt: null,
  ...over,
});

describe('sellerApi', () => {
  beforeEach(() => vi.resetAllMocks());

  /**
   * 파트너 API 와 같은 단언이다. 레퍼런스 백오피스는 화면이 셀러 번호를 실어 보냈고,
   * 그러면 번호만 바꿔 남의 신청서·주문이 열린다. 여기서는 화면이 그 번호를 아예 모른다.
   * 쓰기가 있는 표면이라 이 단언이 더 중요하다 — 읽기는 남의 것을 보는 데서 그치지만
   * 쓰기는 남의 원장을 고친다.
   */
  it('어떤 호출도 셀러·조직 번호를 싣지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {}, headers: {} });
    vi.mocked(api.post).mockResolvedValue({ data: {}, headers: {} });
    vi.mocked(api.put).mockResolvedValue({ data: {}, headers: {} });

    await sellerApi.profile();
    await sellerApi.members();
    await sellerApi.submissions(null, 0, 20);
    await sellerApi.submission(11);
    await sellerApi.createSubmission({
      name: '사과', description: null, price: 1000, stock: 1,
      category: null, imageUrl: null, displayVisible: true,
    });
    await sellerApi.updateSubmission(11, {
      name: '사과', description: null, price: 1000, stock: 1,
      category: null, imageUrl: null, displayVisible: true,
    });
    await sellerApi.submitSubmission(11);
    await sellerApi.orders({ from: '2026-09-01' }, 0, 20);
    await sellerApi.order(10231);
    await sellerApi.registerShipment(10231, 'CJ', '1234');

    const paths = [
      ...vi.mocked(api.get).mock.calls,
      ...vi.mocked(api.post).mock.calls,
      ...vi.mocked(api.put).mock.calls,
    ].map((call) => String(call[0]));

    for (const path of paths) {
      expect(path).not.toMatch(/organization|orgId|sellerId/i);
    }
  });

  /**
   * 게이트가 아니라 사고를 막는 단언이다. 셀러 조직이 아닌 계정의 403·422 는 화면이
   * 그리는 상태라, 전역 인터셉터의 빨간 토스트가 겹치면 설명 위에 경고가 하나 더 뜬다.
   */
  it('profile 만 전역 403 토스트를 끈다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {}, headers: {} });

    await sellerApi.profile();
    await sellerApi.members();

    expect(api.get).toHaveBeenNthCalledWith(1, '/api/seller/profile', { skipAuthToast: true });
    expect(api.get).toHaveBeenNthCalledWith(2, '/api/seller/members');
  });

  /**
   * {@code ?} 는 경로 리터럴에 있어야 한다. 보간이 경로에 붙으면 화면-API 대조 게이트가
   * 경로를 접어 어느 엔드포인트와도 맞추지 못하고, 멀쩡히 불리는 컨트롤러가 "화면 없음"
   * 부채로 집계된다.
   */
  it('쿼리는 ? 뒤에 붙고 빈 조건은 아예 실리지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {}, headers: {} });

    await sellerApi.submissions(null, 0, 20);
    await sellerApi.submissions('SUBMITTED', 1, 20);
    await sellerApi.orders({ from: null, to: null, orderId: null, unshippedOnly: false }, 0, 20);

    expect(api.get).toHaveBeenNthCalledWith(1, '/api/seller/products?page=0&size=20');
    expect(api.get).toHaveBeenNthCalledWith(2, '/api/seller/products?page=1&size=20&status=SUBMITTED');
    expect(api.get).toHaveBeenNthCalledWith(3, '/api/seller/orders?page=0&size=20');
  });

  it('주문번호 0 도 조건이다 — falsy 라고 떨어뜨리면 0번 주문을 못 찾는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {}, headers: {} });

    await sellerApi.orders({ orderId: 0, unshippedOnly: true }, 2, 50);

    expect(api.get).toHaveBeenCalledWith('/api/seller/orders?page=2&size=50&orderId=0&unshippedOnly=true');
  });

  it('단건·하위 경로는 식별자를 경로에 박는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {}, headers: {} });
    vi.mocked(api.post).mockResolvedValue({ data: {}, headers: {} });

    await sellerApi.submission(11);
    await sellerApi.submitSubmission(11);
    await sellerApi.registerShipment(10231, 'CJ대한통운', '1234567890');

    expect(api.get).toHaveBeenCalledWith('/api/seller/products/11');
    expect(api.post).toHaveBeenNthCalledWith(1, '/api/seller/products/11/submit', {});
    expect(api.post).toHaveBeenNthCalledWith(2, '/api/seller/orders/10231/shipment',
      { carrier: 'CJ대한통운', trackingNumber: '1234567890' });
  });

  /**
   * 운영자 경로만 접두사가 다르다. 셀러 경로와 같은 자리에 두면 스코프로 좁힐 수 없는
   * 표면(대상이 전체 신청서다)을 스코프가 있는 것처럼 읽게 된다 — 서버도 이 접두사로 막는다.
   */
  it('심사 경로는 /api/seller/admin/** 다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: {}, headers: {} });
    vi.mocked(api.post).mockResolvedValue({ data: {}, headers: {} });

    await sellerApi.pendingSubmissions(0, 20);
    await sellerApi.approveSubmission(11);
    await sellerApi.rejectSubmission(11, '가격 오기입');

    expect(api.get).toHaveBeenCalledWith('/api/seller/admin/submissions?page=0&size=20');
    expect(api.post).toHaveBeenNthCalledWith(1, '/api/seller/admin/submissions/11/approve', {});
    expect(api.post).toHaveBeenNthCalledWith(2, '/api/seller/admin/submissions/11/reject',
      { reason: '가격 오기입' });
  });
});

describe('표시 도우미', () => {
  /** 환불이 결제를 넘으면 실매출은 음수다. 0 으로 깎으면 화면과 정산이 어긋난다. */
  it('음수 금액을 깎지 않는다', () => {
    expect(formatMoney(-15000)).toBe('-15,000원');
    expect(formatMoney(0)).toBe('0원');
  });

  /**
   * 이 화면의 핵심 단언이다. 승인은 났는데 상품번호가 아직 없는 상태를 '판매중' 으로
   * 그리면, 등록이 실패해 영영 상품이 안 생긴 건과 몇 초 뒤 생길 건이 같은 글자로 보인다 —
   * 아무도 눈치채지 못하는 실패가 된다.
   */
  it('승인 직후와 실제 판매중을 다르게 그린다', () => {
    expect(displayStatus(submission({ status: 'APPROVED', awaitingCatalog: true }))).toBe('등록 처리 중');
    expect(displayStatus(submission({ status: 'APPROVED', awaitingCatalog: false, productId: 5001 })))
      .toBe('판매중');
    expect(displayStatus(submission({ status: 'DRAFT' }))).toBe('작성 중');
    expect(displayStatus(submission({ status: 'SUBMITTED' }))).toBe('심사 대기');
    expect(displayStatus(submission({ status: 'REJECTED', rejectReason: '가격 오기입' }))).toBe('반려');
  });

  it('역할·상태 라벨이 모든 값에 있다', () => {
    expect(Object.keys(MEMBER_ROLE_LABEL)).toEqual(['OWNER', 'MANAGER', 'STAFF']);
    expect(Object.keys(SUBMISSION_STATUS_LABEL)).toEqual(['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED']);
  });
});
