import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  inquiryApi,
  adminInquiryApi,
  INQUIRY_TYPE_REQUIRES,
  type Inquiry,
} from '@/api/inquiry';
import api from '@/api/axios';

vi.mock('@/api/axios', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const waiting: Inquiry = {
  id: 1,
  userId: 7,
  type: 'PRODUCT',
  typeLabel: '상품 문의',
  productId: 10,
  orderId: null,
  subject: '정사이즈인가요',
  content: '평소 270 신습니다',
  secret: false,
  readable: true,
  status: 'WAITING',
  statusLabel: '답변 대기',
  askedAt: '2026-08-20T10:00:00',
  answers: [],
};

const answered: Inquiry = {
  ...waiting,
  id: 2,
  status: 'ANSWERED',
  statusLabel: '답변 완료',
  answers: [
    { id: 100, answeredBy: 3, content: '정사이즈입니다', answeredAt: '2026-08-21T09:00:00' },
  ],
};

describe('inquiryApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  /**
   * 원본(ssg-front)은 USERID 를 폼으로 받아 그대로 저장했다. 남의 아이디를 적으면 남의 이름으로
   * 문의가 등록되고, 그 뒤로는 그 사람만 볼 수 있었다. 여기서는 본문에 작성자가 아예 없다.
   */
  it('등록은 작성자를 보내지 않는다 — 주체는 토큰이 정한다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: waiting });

    await inquiryApi.ask({
      type: 'PRODUCT',
      productId: 10,
      subject: '정사이즈인가요',
      content: '평소 270 신습니다',
      secret: false,
    });

    expect(api.post).toHaveBeenCalledWith('/inquiries', {
      type: 'PRODUCT',
      productId: 10,
      subject: '정사이즈인가요',
      content: '평소 270 신습니다',
      secret: false,
    });
    const [, body] = vi.mocked(api.post).mock.calls[0];
    expect(body).not.toHaveProperty('userId');
  });

  it('내 문의는 종류 없이 부르면 파라미터를 붙이지 않는다 — 전체 탭이 빈 필터를 보내지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [waiting, answered] });

    const result = await inquiryApi.listMine();

    expect(api.get).toHaveBeenCalledWith('/inquiries', { params: undefined });
    expect(result).toHaveLength(2);
  });

  it('종류를 주면 그 종류만 거른다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [waiting] });

    await inquiryApi.listMine('ORDER');

    expect(api.get).toHaveBeenCalledWith('/inquiries', { params: { type: 'ORDER' } });
  });

  it('상품 문의 목록은 상품 경로로 온다 — 사용자별 경로가 아니다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [waiting] });

    await inquiryApi.listForProduct(10);

    expect(api.get).toHaveBeenCalledWith('/inquiries/products/10');
  });

  /**
   * 못 읽는 문의도 줄은 남는다. 감춰 버리면 문의 개수가 보는 사람마다 달라지고, 비밀글을 쓴
   * 본인조차 자기 질문이 등록됐는지 확인할 수 없다. 가림은 서버가 이미 해서 보낸다.
   */
  it('비밀글은 목록에서 빠지지 않고 readable:false 로 온다', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: [{ ...waiting, id: 3, secret: true, readable: false, subject: '비밀글입니다', content: '' }],
    });

    const result = await inquiryApi.listForProduct(10);

    expect(result).toHaveLength(1);
    expect(result[0].readable).toBe(false);
  });

  it('수정은 PUT 이고 종류·대상은 보내지 않는다 — 바뀔 수 있는 것만 보낸다', async () => {
    vi.mocked(api.put).mockResolvedValue({ data: waiting });

    await inquiryApi.edit(1, { subject: '고친 제목', content: '고친 본문', secret: true });

    expect(api.put).toHaveBeenCalledWith('/inquiries/1', {
      subject: '고친 제목',
      content: '고친 본문',
      secret: true,
    });
  });

  it('철회는 DELETE 다', async () => {
    vi.mocked(api.delete).mockResolvedValue({ data: undefined });

    await inquiryApi.withdraw(1);

    expect(api.delete).toHaveBeenCalledWith('/inquiries/1');
  });

  it('종류마다 함께 필요한 대상이 정해져 있다 — 서버 도메인과 같은 규칙이다', () => {
    expect(INQUIRY_TYPE_REQUIRES.PRODUCT).toBe('product');
    expect(INQUIRY_TYPE_REQUIRES.ORDER).toBe('order');
    expect(INQUIRY_TYPE_REQUIRES.GENERAL).toBe('none');
  });
});

describe('adminInquiryApi', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  it('대기열은 전용 경로다 — 전체 목록을 받아 화면에서 거르지 않는다', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: [waiting] });

    const result = await adminInquiryApi.listWaiting();

    expect(api.get).toHaveBeenCalledWith('/admin/inquiries/waiting');
    expect(result[0].status).toBe('WAITING');
  });

  it('답변 등록은 답변자를 보내지 않는다 — 토큰이 정한다', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: answered });

    const result = await adminInquiryApi.answer(2, '정사이즈입니다');

    expect(api.post).toHaveBeenCalledWith('/admin/inquiries/2/answers', {
      content: '정사이즈입니다',
    });
    expect(result.status).toBe('ANSWERED');
  });

  /**
   * 원본은 답변 번호 하나만 보고 지워서 다른 문의의 답변이 사라졌다. 경로에 문의 번호가 함께
   * 들어가야 서버가 짝을 대조할 수 있다.
   */
  it('답변 삭제 경로에는 문의 번호가 함께 들어간다', async () => {
    vi.mocked(api.delete).mockResolvedValue({ data: { ...answered, status: 'WAITING', answers: [] } });

    await adminInquiryApi.deleteAnswer(2, 100);

    expect(api.delete).toHaveBeenCalledWith('/admin/inquiries/2/answers/100');
  });

  /** 상태는 저장된 칼럼이 아니라 답변 유무다 — 지우면 같은 순간 대기로 돌아온다. */
  it('답변을 지우면 서버가 대기로 돌아온 문의를 돌려준다', async () => {
    vi.mocked(api.delete).mockResolvedValue({
      data: { ...answered, status: 'WAITING', statusLabel: '답변 대기', answers: [] },
    });

    const result = await adminInquiryApi.deleteAnswer(2, 100);

    expect(result.status).toBe('WAITING');
    expect(result.answers).toHaveLength(0);
  });
});
