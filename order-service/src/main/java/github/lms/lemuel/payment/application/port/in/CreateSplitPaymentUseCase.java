package github.lms.lemuel.payment.application.port.in;

import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.TenderType;
import github.lms.lemuel.payment.domain.exception.PaymentInvariantViolationException;

import java.math.BigDecimal;
import java.util.List;

public interface CreateSplitPaymentUseCase {

    /**
     * 분할결제 생성. 여러 지불수단으로 1 결제를 처리.
     *
     * <p>Tender 처리 흐름:
     * <ul>
     *   <li>외부 PG tender (CARD/KAKAO_PAY 등): PgRouter 경유 authorize → capture</li>
     *   <li>POINT tender: 포인트 원장에서 실제 차감(잔액 부족이면 결제 자체가 실패한다)</li>
     *   <li>GIFT_CARD tender: 기프트카드 원장에서 실제 차감(만료 임박 카드부터, gift-card-ledger.md)</li>
     * </ul>
     *
     * <p>일부 tender 가 실패하면 이미 처리된 다른 tender 를 보상 환불 (Saga 형태) — 본 구현은
     * 트랜잭션 안에서 시도하므로 RuntimeException 발생 시 자동 롤백.
     *
     * @param orderId 주문 ID
     * @param tenders 지불수단 라인 목록. <b>1 개 이상</b> — 포인트·기프트카드 전액 결제가 이 경로로
     *                들어온다(일반 결제 경로는 지불수단을 모델링하지 않아 원장 차감이 걸릴 자리가 없다)
     * @param actorUserId 결제 주체. <b>JWT 에서 파생</b>한 값이어야 한다 — 요청 본문의 userId 를
     *                    그대로 신뢰하면 남의 포인트로 결제할 수 있다(IDOR).
     * @return 생성·캡처 완료된 PaymentDomain
     */
    PaymentDomain createWithTenders(Long orderId, List<TenderRequest> tenders, Long actorUserId);

    record TenderRequest(TenderType type, BigDecimal amount) {
        public TenderRequest {
            if (type == null) throw new PaymentInvariantViolationException("tender type 필수");
            if (amount == null || amount.signum() <= 0) {
                throw new PaymentInvariantViolationException("amount 양수 필수");
            }
        }
    }
}
