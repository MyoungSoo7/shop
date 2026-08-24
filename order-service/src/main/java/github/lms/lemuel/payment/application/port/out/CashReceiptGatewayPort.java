package github.lms.lemuel.payment.application.port.out;

import github.lms.lemuel.payment.domain.CashReceipt;

/**
 * 현금영수증 발급 대행(PG/국세청 연동) 아웃바운드 포트.
 *
 * <p>발급도 취소도 <b>외부 왕복</b>이라 우리 DB 커밋과 원자적이지 않다. 그래서 반환은 성공/실패
 * 결과 객체이고, 상태 전이는 도메인({@code CashReceipt})이 그 결과를 받아 수행한다 —
 * 어댑터가 상태를 직접 바꾸면 "외부는 실패했는데 우리는 발급됨"이 조용히 생긴다.
 *
 * <p>레거시 커머스(ssgb2e {@code OrderCashReceiptServiceImpl})는 PG 모듈에 HMAC 서명한 전문을
 * 직접 POST 했다. 그 자리가 이 포트의 실제 구현이 들어갈 곳이다.
 */
public interface CashReceiptGatewayPort {

    /** 발급 요청. 승인번호를 받으면 성공. */
    Result issue(CashReceipt receipt);

    /** 발급 취소 요청. */
    Result cancel(CashReceipt receipt, String reason);

    /**
     * @param success        외부 처리 성공 여부
     * @param approvalNumber 발급 승인번호(취소·실패 시 null)
     * @param message        실패 사유(성공 시 null)
     */
    record Result(boolean success, String approvalNumber, String message) {

        public static Result issued(String approvalNumber) {
            return new Result(true, approvalNumber, null);
        }

        public static Result ok() {
            return new Result(true, null, null);
        }

        public static Result failed(String message) {
            return new Result(false, null, message);
        }
    }
}
