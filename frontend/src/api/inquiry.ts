import api from './axios';

/**
 * 문의 — order-service {@code InquiryController} · {@code AdminInquiryController}.
 *
 * <h3>왜 생겼나</h3>
 * <p>지금까지 "이 상품 사이즈가 정사이즈인가요"를 물을 곳이 리뷰밖에 없었다. 리뷰는 <b>산 사람이</b>
 * 쓰는 것이라, 사기 전에 묻고 싶은 사람은 쓸 수 없다. 그래서 실무의 우회책은 고객센터 전화이거나,
 * 남의 리뷰에 대댓글을 다는 것이었다 — 둘 다 답이 상품 페이지에 남지 않아 같은 질문이 계속 반복된다.
 *
 * <h3>상태는 저장된 값이 아니다</h3>
 * <p>{@link Inquiry.status} 는 <b>답변 유무에서 계산된</b> 값이다. 원본(ssg-front)은 이 값을 질문 행의
 * 칼럼으로 들고 있었는데, 답변 삭제 경로가 그것을 되돌리지 않아 답변이 사라진 뒤에도 목록은
 * "답변 완료"라 말하고 상세를 열면 아무것도 없었다. 화면은 이 값을 그대로 믿어도 된다.
 *
 * <h3>비밀글은 감추는 것이 아니라 가리는 것이다</h3>
 * <p>못 읽는 문의도 목록에서 <b>줄은 남고</b> {@link Inquiry.readable} 이 false 로 온다. 감춰 버리면
 * 작성자는 자기 질문이 등록됐는지 확인할 수 없고, 문의 개수도 보는 사람마다 달라진다. 서버가
 * 제목·본문을 이미 가려서 보내므로 화면은 {@code readable} 로 자물쇠만 그리면 된다.
 *
 * <h3>답변이 달리면 못 고친다</h3>
 * <p>수정·철회는 답변 전에만 된다(그 뒤에는 409). 답을 받은 뒤 질문을 바꾸면 서로 맞지 않는
 * 질문·답 한 쌍이 남기 때문이다. 화면은 {@code status === 'ANSWERED'} 이면 버튼을 내린다.
 */

/** 서버 {@code InquiryType} 과 같은 집합. */
export type InquiryTypeValue = 'PRODUCT' | 'ORDER' | 'GENERAL';

/** 서버 {@code InquiryStatus} 와 같은 집합. 저장된 값이 아니라 답변 유무에서 계산된다. */
export type InquiryStatusValue = 'WAITING' | 'ANSWERED';

/** 종류별로 무엇을 함께 요구하는가. 서버 도메인이 강제하는 것과 같은 규칙이다. */
export const INQUIRY_TYPE_REQUIRES: Record<InquiryTypeValue, 'product' | 'order' | 'none'> = {
  PRODUCT: 'product',
  ORDER: 'order',
  GENERAL: 'none',
};

export interface InquiryAnswer {
  id: number;
  answeredBy: number;
  content: string;
  answeredAt: string;
}

export interface Inquiry {
  id: number;
  userId: number;
  type: InquiryTypeValue;
  /** 화면이 enum 을 다시 번역하지 않도록 서버가 한글 이름을 함께 준다. */
  typeLabel: string;
  productId: number | null;
  orderId: number | null;
  subject: string;
  content: string;
  secret: boolean;
  /** 본문을 볼 수 있는가. false 면 제목·본문이 이미 가려진 채 온다. */
  readable: boolean;
  status: InquiryStatusValue;
  statusLabel: string;
  askedAt: string;
  answers: InquiryAnswer[];
}

export interface AskInquiryRequest {
  type: InquiryTypeValue;
  productId?: number | null;
  orderId?: number | null;
  subject: string;
  content: string;
  secret: boolean;
}

export interface EditInquiryRequest {
  subject: string;
  content: string;
  secret: boolean;
}

export const inquiryApi = {
  /**
   * POST — 등록.
   *
   * <p>작성자를 보내지 않는다. 토큰이 정한다 — 원본은 {@code USERID} 를 폼으로 받아 그대로
   * 저장해서, 남의 아이디를 적으면 남의 이름으로 문의가 등록됐다.
   */
  ask: async (request: AskInquiryRequest): Promise<Inquiry> => {
    const response = await api.post<Inquiry>('/inquiries', request);
    return response.data;
  },

  /** GET — 내 문의. 최신순. type 을 주면 그 종류만 본다. */
  listMine: async (type?: InquiryTypeValue): Promise<Inquiry[]> => {
    const response = await api.get<Inquiry[]>('/inquiries', {
      params: type ? { type } : undefined,
    });
    return response.data;
  },

  /** GET — 상품에 달린 문의. 비밀글은 줄만 남고 {@code readable:false} 로 온다. */
  listForProduct: async (productId: number): Promise<Inquiry[]> => {
    const response = await api.get<Inquiry[]>(`/inquiries/products/${productId}`);
    return response.data;
  },

  /** GET — 상세. 남의 비밀 문의면 403 이다. */
  get: async (inquiryId: number): Promise<Inquiry> => {
    const response = await api.get<Inquiry>(`/inquiries/${inquiryId}`);
    return response.data;
  },

  /** PUT — 수정. 답변이 달린 뒤에는 409 다. */
  edit: async (inquiryId: number, request: EditInquiryRequest): Promise<Inquiry> => {
    const response = await api.put<Inquiry>(`/inquiries/${inquiryId}`, request);
    return response.data;
  },

  /** DELETE — 철회. 답변이 달린 뒤에는 409 다. */
  withdraw: async (inquiryId: number): Promise<void> => {
    await api.delete(`/inquiries/${inquiryId}`);
  },
};

export const adminInquiryApi = {
  /** GET — 답변 대기 목록. 오래된 순, 먼저 물어본 사람이 먼저다. */
  listWaiting: async (): Promise<Inquiry[]> => {
    const response = await api.get<Inquiry[]>('/admin/inquiries/waiting');
    return response.data;
  },

  /** GET — 상세. 비밀글도 그대로 온다(답하려면 읽어야 한다). */
  get: async (inquiryId: number): Promise<Inquiry> => {
    const response = await api.get<Inquiry>(`/admin/inquiries/${inquiryId}`);
    return response.data;
  },

  /** POST — 답변 등록. 다는 순간 상태가 '답변 완료'가 된다. */
  answer: async (inquiryId: number, content: string): Promise<Inquiry> => {
    const response = await api.post<Inquiry>(`/admin/inquiries/${inquiryId}/answers`, { content });
    return response.data;
  },

  /**
   * DELETE — 답변 삭제. 지우는 순간 상태가 다시 '답변 대기'다.
   *
   * <p>어느 문의의 답변인지까지 대조하므로 다른 문의의 답변 번호를 넣으면 404 다 — 원본은
   * 답변 번호 하나만 보고 지워서 남의 문의 답변이 사라졌다.
   */
  deleteAnswer: async (inquiryId: number, answerId: number): Promise<Inquiry> => {
    const response = await api.delete<Inquiry>(`/admin/inquiries/${inquiryId}/answers/${answerId}`);
    return response.data;
  },
};
