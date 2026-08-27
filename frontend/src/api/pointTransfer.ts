import api from './axios';

/**
 * 회원 간 포인트 선물 — order-service {@code PointTransferController}.
 *
 * <p><b>왜 생겼나.</b> 지금까지 내 포인트를 다른 사람에게 넘기는 길은 <b>운영자에게 부탁하는 것</b>
 * 하나뿐이었다. 운영자가 한쪽에서 수기 차감하고 다른 쪽에서 수기 지급하는 두 번의 조작이었고,
 * 그 사이에 손이 미끄러지면 어느 한쪽만 반영됐다. 이 API 는 그 두 걸음을 <b>한 트랜잭션</b>으로
 * 묶는다.
 *
 * <h3>보내는 이는 본문에 없다</h3>
 * <p>{@link TransferRequest} 에 보내는 사람 칸이 없는 것은 실수가 아니다. 주체를 요청이 지정할 수
 * 있으면 남의 포인트를 보내는 요청을 만들 수 있다. 서버는 토큰에서만 보내는 이를 읽는다.
 *
 * <h3>requestId 는 화면이 만들고, 실패해도 바꾸지 않는다</h3>
 * <p>같은 (보내는 이, requestId) 로 두 번 오면 서버는 두 번째를 <b>다시 실행하지 않고</b> 첫 결과를
 * 그대로 돌려준다({@link TransferResult.alreadyProcessed} 가 true). 네트워크가 끊겨 응답을 못 받은
 * 요청은 이미 처리됐을 수 있으므로, 재시도할 때 키를 새로 뽑으면 그게 곧 <b>두 번 보내기</b>다.
 * 그래서 키는 <b>전송이 성공한 뒤에만</b> 새로 뽑는다({@link newRequestId}).
 *
 * <h3>이메일과 이름을 둘 다 받는 이유</h3>
 * <p>이름 칸은 편의가 아니라 <b>오타 방지</b>다. 이메일 한 글자가 틀려도 그게 실재하는 다른 회원이면
 * 포인트는 그 사람에게 간다. 되돌리는 API 는 없다 — 받은 사람이 이미 썼을 수 있기 때문이다.
 * 둘 중 하나라도 어긋나면 서버는 사유를 구분하지 않고 같은 문구로 거절한다(가입 여부를 캐낼 수
 * 있으므로).
 */

/** 전송 요청. 보내는 사람 칸은 없다 — 서버가 토큰에서 읽는다. */
export interface TransferRequest {
  /** 멱등 키. 성공할 때까지 같은 값을 유지한다. */
  requestId: string;
  recipientEmail: string;
  /** 받는 분 실명. 이메일과 함께 맞아야 전송된다. */
  recipientName: string;
  amount: number;
  /** 함께 보낼 한마디. 없어도 된다(최대 200자). */
  message?: string | null;
}

export interface TransferResult {
  transferNo: string;
  /** 서버가 가린 이메일(`fr****@example.com`). 화면이 직접 가리지 않는다. */
  recipientEmail: string;
  recipientName: string;
  amount: number;
  /** 보낸 뒤 내 잔액. */
  remainingBalance: number;
  transferredAt: string;
  /** true 면 이 요청은 재전송이었고 서버는 아무것도 다시 하지 않았다. */
  alreadyProcessed: boolean;
}

/** 주고받은 이력 한 줄. `outgoing` 이 true 면 내가 보낸 것이다. */
export interface TransferHistoryEntry {
  transferNo: string;
  outgoing: boolean;
  /** 상대방 이름. 탈퇴 등으로 사라졌으면 서버가 대체 문구를 준다. */
  counterpartName: string;
  amount: number;
  message: string | null;
  transferredAt: string;
}

/**
 * 멱등 키를 새로 뽑는다.
 *
 * <p>{@code crypto.randomUUID} 가 없는 환경(구형 브라우저·비보안 컨텍스트)에서도 화면이 멈추면
 * 안 되므로 시간+난수로 물러선다. 이 값은 <b>서버 안에서 보내는 이별로만</b> 유일하면 되므로
 * 전역 유일성이 필요하지 않다.
 */
export const newRequestId = (): string => {
  const webCrypto = globalThis.crypto;
  if (webCrypto && typeof webCrypto.randomUUID === 'function') {
    return webCrypto.randomUUID();
  }
  return `pt-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
};

// 경로는 전체 리터럴로 적는다. 조각을 이어 붙이면 사람 눈에도, 저장소의 화면-API 대조
// 게이트(api-screen-gate)에도 어떤 엔드포인트를 부르는지 보이지 않는다.
export const pointTransferApi = {
  /** POST — 같은 requestId 로 다시 부르면 처음 결과가 그대로 온다. */
  send: async (body: TransferRequest): Promise<TransferResult> =>
    (await api.post<TransferResult>('/api/points/transfers', body)).data,

  /** GET — 보낸 것과 받은 것을 섞어 최신순으로. */
  history: async (limit = 20): Promise<TransferHistoryEntry[]> =>
    (await api.get<TransferHistoryEntry[]>('/api/points/transfers', { params: { limit } })).data,
};
