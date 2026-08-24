package github.lms.lemuel.payment.application;

import github.lms.lemuel.common.log.LogSafe;
import github.lms.lemuel.payment.application.port.in.CapturePaymentPort;
import github.lms.lemuel.payment.application.port.in.CreatePaymentCommand;
import github.lms.lemuel.payment.application.port.in.CreatePaymentPort;
import github.lms.lemuel.payment.application.port.out.LoadOrderPort;
import github.lms.lemuel.payment.application.port.out.LoadPaymentPort;
import github.lms.lemuel.payment.application.port.out.PaymentIdempotencyPort;
import github.lms.lemuel.payment.application.port.out.SavePaymentPort;
import github.lms.lemuel.payment.domain.PaymentDomain;
import github.lms.lemuel.payment.domain.PaymentGateway;
import github.lms.lemuel.payment.domain.exception.OrderNotFoundException;
import github.lms.lemuel.payment.domain.exception.PaymentAmountMismatchException;
import github.lms.lemuel.payment.domain.exception.PaymentNotFoundException;
import github.lms.lemuel.payment.domain.exception.PaymentIdempotencyConflictException;
import github.lms.lemuel.payment.domain.exception.PaymentOwnershipException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 토스페이먼츠 결제 확인 서비스
 * Flow: 멱등 replay 확인 → 주문 대조(소유권·금액) → Toss API 확인 → READY 결제 생성 → AUTHORIZED → CAPTURED
 *
 * 복원력:
 *   - PG 호출은 {@link TossConfirmApiClient}(별도 빈)이 담당 — CircuitBreaker + Retry (Resilience4j)
 *   - <b>별도 빈이어야</b> 스프링 AOP 프록시를 통과한다. 예전처럼 같은 클래스 안에서 자기호출하면
 *     어드바이스가 걸리지 않아 재시도·서킷이 조용히 무력화된다
 *     (회귀 차단: {@code scripts/harness/test/aop-proxy-gate.test.mjs}).
 *   - RestTemplate connect/read timeout 설정으로 쓰레드 고갈 방지
 *   - 4xx (Toss 비즈니스 오류) 는 서킷 판정·재시도 모두에서 제외
 *
 * <h2>PG 호출 전에 반드시 끝내야 하는 검증 2가지</h2>
 * <ol>
 *   <li><b>소유권</b> — 요청 본문의 {@code dbOrderId} 를 믿으면 남의 주문번호만 알아도 그 주문을
 *       결제 완료로 만들 수 있다. 주문 소유자는 DB 에서 읽어 JWT 주체와 대조한다.</li>
 *   <li><b>금액</b> — Toss 는 "결제창 개설 금액 == confirm 금액" 만 본다. 결제창 금액도 브라우저가
 *       정하므로, 서버가 주문 금액과 이어주지 않으면 저가 결제창으로 전액 주문을 결제 처리할 수
 *       있다. PG 는 이 공격을 못 막는다 — 대조할 수 있는 주체는 주문 금액을 아는 우리뿐이다.</li>
 * </ol>
 * 둘 다 <b>돈이 움직이기 전</b>에 판정해야 의미가 있으므로 {@code tossConfirmApiClient.confirm} 앞에 둔다.
 *
 * <h2>멱등</h2>
 * 승인은 POST 다 — 네트워크 재시도·더블클릭이 같은 승인을 두 번 부를 수 있다. 예전에는 방어선이
 * 하류 UNIQUE({@code uq_payments_pg_txn}·{@code idx_payments_order_id_unique}) 뿐이라 이중 결제행은
 * 막히되 사용자에게는 500 이 나갔다. 이제 {@code payment_idempotency} 로 최초 결과를 replay 한다.
 * 키는 클라이언트의 {@code Idempotency-Key}, 없으면 결제창이 발급한 {@code paymentKey} 다 —
 * 헤더를 보내지 않는 기존 클라이언트도 그대로 보호된다.
 */
@Service
@Transactional
public class TossPaymentService {

    private static final Logger log = LoggerFactory.getLogger(TossPaymentService.class);

    private final TossConfirmApiClient tossConfirmApiClient;
    private final CreatePaymentPort createPaymentPort;
    private final SavePaymentPort savePaymentPort;
    private final CapturePaymentPort capturePaymentPort;
    private final LoadOrderPort loadOrderPort;
    private final LoadPaymentPort loadPaymentPort;
    private final PaymentIdempotencyPort paymentIdempotencyPort;

    public TossPaymentService(TossConfirmApiClient tossConfirmApiClient,
                              CreatePaymentPort createPaymentPort,
                              SavePaymentPort savePaymentPort,
                              CapturePaymentPort capturePaymentPort,
                              LoadOrderPort loadOrderPort,
                              LoadPaymentPort loadPaymentPort,
                              PaymentIdempotencyPort paymentIdempotencyPort) {
        this.tossConfirmApiClient = tossConfirmApiClient;
        this.createPaymentPort = createPaymentPort;
        this.savePaymentPort = savePaymentPort;
        this.capturePaymentPort = capturePaymentPort;
        this.loadOrderPort = loadOrderPort;
        this.loadPaymentPort = loadPaymentPort;
        this.paymentIdempotencyPort = paymentIdempotencyPort;
    }

    /**
     * 토스페이먼츠 최종 결제 승인
     *
     * @param callerUserId   JWT 주체의 사용자 ID. {@code null} 이면 소유권 대조를 건너뛴다
     *                       (운영자 ADMIN/MANAGER 경로 — 웹 어댑터가 판정해 넘긴다).
     * @param idempotencyKey 클라이언트가 준 {@code Idempotency-Key}. 비어 있으면 {@code paymentKey} 를 쓴다.
     */
    public PaymentDomain confirmTossPayment(Long dbOrderId, String paymentKey, String tossOrderId,
                                            Long amount, Long callerUserId, String idempotencyKey) {
        log.info("토스 결제 확인 시작: dbOrderId={}, tossOrderId={}, amount={}",
                dbOrderId, LogSafe.of(tossOrderId), amount);

        String key = effectiveKey(idempotencyKey, paymentKey, callerUserId, "single");
        Optional<PaymentDomain> replayed = replay(key);
        if (replayed.isPresent()) {
            PaymentDomain prior = replayed.get();
            // 같은 키를 <b>다른 주문</b>에 다시 쓰면 무관한 결제를 성공으로 돌려주게 된다.
            // 재시도의 정의는 "같은 요청을 다시"이므로, 요청이 달라졌으면 replay 가 아니라 거절이다.
            if (!prior.getOrderId().equals(dbOrderId)) {
                throw new PaymentIdempotencyConflictException(key, prior.getOrderId(), dbOrderId);
            }
            log.info("멱등 승인 replay: key={}, paymentId={}", LogSafe.of(key), prior.getId());
            return prior;
        }

        // 돈이 움직이기 전에 — 소유권·금액을 서버가 보관한 주문으로 대조한다.
        LoadOrderPort.OrderInfo order = requireOwnedOrder(dbOrderId, callerUserId);
        requireAmountMatches(dbOrderId, order.getAmount(), amount);

        tossConfirmApiClient.confirm(paymentKey, tossOrderId, amount);

        PaymentDomain payment = createPaymentPort.createPayment(
                new CreatePaymentCommand(dbOrderId, "TOSS_PAYMENTS")
        );

        // pgTransactionId 는 "PROVIDER:txn" prefix 규칙을 따라야 PgRouter.resolveByTransactionId 가
        // capture/refund 시 올바른 PG 로 라우팅한다. raw paymentKey 를 그대로 저장하면 prefix 미인식 →
        // MOCK 폴백 → "어댑터 없음: provider=MOCK" 로 capture 가 죽는다. TOSS: prefix 를 붙인다.
        payment.authorize(PaymentGateway.TOSS.prefix() + PaymentGateway.TRANSACTION_ID_DELIMITER + paymentKey);
        savePaymentPort.save(payment);

        PaymentDomain captured = capturePaymentPort.capturePayment(payment.getId());

        // 매핑은 마지막에 — dup 키면 여기서 제약 위반이 나고 같은 트랜잭션의 결제 생성까지 롤백된다.
        paymentIdempotencyPort.save(key, captured.getId());

        log.info("토스 결제 완료: paymentId={}", captured.getId());
        return captured;
    }

    /**
     * 토스페이먼츠 장바구니 일괄 결제 확인
     *
     * <p>단건과 같은 두 검증을 적용하되, 금액은 <b>주문 금액 합계 == 총 승인액</b> 으로 본다.
     * 한 건이라도 소유자가 다르거나 합계가 어긋나면 전체를 거절한다 — 부분 승인은 대사에서
     * "돈은 들어왔는데 어느 주문 것인지 모르는" 상태를 만든다.
     *
     * @param callerUserId   JWT 주체의 사용자 ID({@code null} = 운영자 경로, 소유권 대조 생략)
     * @param idempotencyKey 클라이언트가 준 {@code Idempotency-Key}. 비어 있으면 {@code paymentKey} 를 쓴다.
     */
    public List<PaymentDomain> confirmTossCartPayment(List<Long> orderIds, String paymentKey,
                                                      String tossOrderId, Long totalAmount,
                                                      Long callerUserId, String idempotencyKey) {
        log.info("토스 장바구니 결제 확인 시작: orderIds={}, totalAmount={}", orderIds, totalAmount);

        String key = effectiveKey(idempotencyKey, paymentKey, callerUserId, "cart");
        Optional<PaymentDomain> replayed = replay(key);
        if (replayed.isPresent()) {
            // 일괄 승인은 키 1개에 결제 N건이라, 매핑은 대표 1건만 보관한다. replay 는 그 결제의
            // 주문들을 다시 조회하지 않고 보관된 건만 돌려준다 — 재시도의 목적은 "이중 승인 방지"
            // 이지 응답 재구성이 아니기 때문이다.
            log.info("멱등 일괄 승인 replay: key={}, paymentId={}", LogSafe.of(key), replayed.get().getId());
            return List.of(replayed.get());
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (Long orderId : orderIds) {
            sum = sum.add(requireOwnedOrder(orderId, callerUserId).getAmount());
        }
        if (sum.compareTo(BigDecimal.valueOf(totalAmount)) != 0) {
            throw new PaymentAmountMismatchException(
                    "일괄 결제 금액이 주문 금액 합계와 일치하지 않습니다: orderIds=" + orderIds
                            + ", 주문합계=" + sum + ", 요청금액=" + totalAmount);
        }

        tossConfirmApiClient.confirm(paymentKey, tossOrderId, totalAmount);

        List<PaymentDomain> results = new ArrayList<>();
        for (Long orderId : orderIds) {
            PaymentDomain payment = createPaymentPort.createPayment(
                    new CreatePaymentCommand(orderId, "TOSS_PAYMENTS")
            );
            // pgTransactionId 는 "PROVIDER:txn" prefix 규칙을 따라야 PgRouter.resolveByTransactionId 가
            // capture/refund 시 올바른 PG 로 라우팅한다. raw paymentKey 를 그대로 저장하면 prefix 미인식 →
            // MOCK 폴백 → "어댑터 없음: provider=MOCK" 로 capture 가 죽는다. TOSS: prefix 를 붙인다.
            payment.authorize(PaymentGateway.TOSS.prefix() + PaymentGateway.TRANSACTION_ID_DELIMITER + paymentKey);
            savePaymentPort.save(payment);

            PaymentDomain captured = capturePaymentPort.capturePayment(payment.getId());
            results.add(captured);
            log.info("장바구니 항목 결제 완료: orderId={}, paymentId={}", orderId, captured.getId());
        }

        if (!results.isEmpty()) {
            paymentIdempotencyPort.save(key, results.get(0).getId());
        }

        log.info("토스 장바구니 결제 전체 완료: {}건", results.size());
        return results;
    }

    // ── 검증 ──

    /** 클라이언트 헤더가 없으면 승인 시도를 유일하게 가리키는 {@code paymentKey} 로 대체한다. */
    private static String effectiveKey(String idempotencyKey, String paymentKey,
                                       Long callerUserId, String operation) {
        String raw = (idempotencyKey == null || idempotencyKey.isBlank()) ? paymentKey : idempotencyKey;
        // ★ 키를 <b>호출자와 연산</b>으로 스코프한다. 저장 키는 전역이고 값은 클라이언트가 정하므로,
        //   스코프가 없으면 남이 쓴 키를 그대로 보내는 것만으로 그 사람의 결제 응답을 받아 볼 수 있고
        //   (정보 노출), 같은 키를 단건↔장바구니로 돌려 쓰면 무관한 결과가 조용히 replay 된다.
        //   운영자 경로(callerUserId=null)는 "op" 로 묶는다 — 사용자 네임스페이스와 섞이지 않게.
        return operation + ':' + (callerUserId == null ? "op" : callerUserId.toString()) + ':' + raw;
    }

    /**
     * 이미 처리된 키면 그때 만든 결제를 돌려준다.
     *
     * <p>매핑은 있는데 결제가 없다면 데이터가 깨진 것이다 — 조용히 재승인해서 이중 결제를 만드는
     * 대신 {@link PaymentNotFoundException} 으로 드러낸다.
     */
    private Optional<PaymentDomain> replay(String key) {
        return paymentIdempotencyPort.findPaymentId(key)
                .map(paymentId -> loadPaymentPort.loadById(paymentId)
                        .orElseThrow(() -> new PaymentNotFoundException(paymentId)));
    }

    /**
     * 주문을 읽고 소유권을 대조한다.
     *
     * <p>{@code callerUserId} 가 {@code null} 이면 운영자 경로라 대조를 건너뛴다. 반대로 <b>주문
     * 소유자를 알 수 없으면 거부</b>한다 — 대조 불가를 통과로 처리하면 게이트가 조용히 꺼진다.
     */
    private LoadOrderPort.OrderInfo requireOwnedOrder(Long orderId, Long callerUserId) {
        LoadOrderPort.OrderInfo order = loadOrderPort.loadOrder(orderId);
        if (order == null) {
            throw new OrderNotFoundException(orderId);
        }
        if (callerUserId == null) {
            return order;
        }
        if (order.getUserId() == null || !order.getUserId().equals(callerUserId)) {
            throw new PaymentOwnershipException(orderId);
        }
        return order;
    }

    /**
     * 승인 요청 금액과 주문 금액을 <b>값</b>으로 비교한다({@code compareTo}) — {@code equals} 는
     * 스케일까지 보므로 {@code 10000.00} 과 {@code 10000} 을 다르다고 판정한다.
     *
     * <p>과납도 거절한다. 더 낸 돈은 손해가 아닌 듯 보이지만, 주문 금액과 다른 입금은 정산·대사에서
     * 출처 불명 금액이 되어 결국 사람이 손으로 풀어야 한다.
     */
    private static void requireAmountMatches(Long orderId, BigDecimal orderAmount, Long requestedAmount) {
        BigDecimal requested = BigDecimal.valueOf(requestedAmount);
        if (orderAmount == null || orderAmount.compareTo(requested) != 0) {
            throw new PaymentAmountMismatchException(orderId, orderAmount, requested);
        }
    }
}
