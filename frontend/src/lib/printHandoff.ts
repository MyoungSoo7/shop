/**
 * 인쇄 창으로 넘기는 **보조 표시값** 핸드오프.
 *
 * 인쇄 페이지는 문서의 정본 데이터를 자기가 API 로 다시 조회한다(새로고침·직접 진입에도
 * 같은 문서가 나와야 하므로). 다만 목록 화면에만 있고 상세 API 에는 없는 표시값
 * (주문자명·상품명 등)은 여는 쪽에서 넘겨줘야 한다.
 *
 * 이 값을 **쿼리스트링으로 넘기지 않는 이유**: 주문자명은 개인정보이고, SPA 라우트는
 * nginx 를 거쳐 index.html 을 받으므로 쿼리가 웹서버 액세스 로그와 브라우저 히스토리에
 * 그대로 남는다. 같은 오리진의 sessionStorage 는 창을 닫으면 사라지고 서버에 안 남는다.
 *
 * 값이 없어도 인쇄 페이지는 해당 행만 생략하고 정상 출력된다(항상 선택적).
 */

const KEY_PREFIX = 'print:handoff:';

/**
 * 문서 종류 키 — 여는 쪽과 인쇄 쪽이 같은 값을 써야 한다.
 * 페이로드 타입도 여기 함께 둔다: 목록 화면이 인쇄 페이지 모듈을 값으로 import 하면
 * 무거운 인쇄 청크가 목록 번들에 딸려 들어와 lazy 분할이 무의미해지기 때문이다.
 */
export const PRINT_DOC = {
  settlement: 'settlement',
} as const;

/** 정산서: 목록에만 있고 상세 API 에 없는 표시값. 금액은 넘기지 않는다(정본은 상세 API). */
export interface SettlementPrintHandoff {
  ordererName?: string;
  productName?: string;
}

const storage = (): Storage | null => {
  try {
    return window.sessionStorage;
  } catch {
    // 프라이빗 모드·스토리지 차단 환경 — 핸드오프 없이 동작한다.
    return null;
  }
};

/** 여는 쪽에서 호출: 인쇄 창이 읽어갈 보조 표시값을 심는다. */
export const putPrintHandoff = <T>(docType: string, id: string | number, payload: T): void => {
  const store = storage();
  if (!store) return;
  try {
    store.setItem(`${KEY_PREFIX}${docType}:${id}`, JSON.stringify(payload));
  } catch {
    // 용량 초과 등 — 보조값일 뿐이라 조용히 포기한다.
  }
};

/**
 * 인쇄 쪽에서 호출: 보조 표시값을 읽는다. 없으면 null.
 *
 * 읽고 지우지 **않는다** — 지우면 인쇄 창에서 F5 를 누르는 순간 주문자·상품명 줄만
 * 사라져 같은 문서가 다른 내용으로 출력된다. 값은 창(sessionStorage)과 수명을 같이 한다.
 */
export const readPrintHandoff = <T>(docType: string, id: string | number): T | null => {
  const store = storage();
  if (!store) return null;
  try {
    const raw = store.getItem(`${KEY_PREFIX}${docType}:${id}`);
    return raw === null ? null : (JSON.parse(raw) as T);
  } catch {
    return null;
  }
};

/** 인쇄 창을 연다. 팝업 차단 시 null 을 반환하므로 호출부가 안내할 수 있다. */
export const openPrintWindow = (path: string): Window | null =>
  window.open(path, '_blank', 'noopener,noreferrer');
