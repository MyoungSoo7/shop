/**
 * 토스페이먼츠 결제창 v1 스크립트(`https://js.tosspayments.com/v1/payment`)가 붙이는 전역 선언.
 *
 * <p>이 경로는 npm SDK 가 아니라 CDN 스크립트를 런타임에 주입해 쓰므로 타입이 딸려오지 않는다.
 * `window as any` 로 지우는 대신 <b>실제로 호출하는 표면만</b> 최소로 선언한다 — 오타나 잘못된
 * 옵션 키가 컴파일 시점에 잡힌다.
 *
 * <p>스크립트 로드 전에는 존재하지 않으므로 <b>optional</b> 로 둔다. 호출부는 `?.()` 로 접근하고
 * 로드 실패를 명시적으로 다뤄야 한다.
 */
interface TossPaymentsRequestOptions {
  amount: number;
  orderId: string;
  orderName: string;
  customerName?: string;
  successUrl: string;
  failUrl: string;
}

interface TossPaymentsInstance {
  /** 결제창을 띄운다. 성공하면 successUrl 로 리다이렉트되므로 이후 코드는 실행되지 않는다. */
  requestPayment(method: '카드', options: TossPaymentsRequestOptions): Promise<void>;
}

interface Window {
  TossPayments?: (clientKey: string) => TossPaymentsInstance;
}
