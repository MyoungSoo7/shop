package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.in.IdempotentMultiItemOrderUseCase;
import github.lms.lemuel.order.application.port.out.DistributedLockPort;
import github.lms.lemuel.order.application.port.out.LoadOrderPort;
import github.lms.lemuel.order.application.port.out.OrderIdempotencyPort;
import github.lms.lemuel.order.domain.Order;
import github.lms.lemuel.order.domain.exception.OrderNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 중복 주문 제출 방지 오케스트레이터 — 분산 락(뮤텍스) + Idempotency-Key 멱등.
 *
 * <p>동일 {@code Idempotency-Key} 의 중복 제출(더블클릭·클라이언트 재시도)을 두 겹으로 막는다:
 * <ol>
 *   <li><b>분산 락</b>({@link DistributedLockPort}): 동일 키 요청을 직렬화해 동시 중복이 각자
 *       주문 생성·재고 차감을 수행하는 <i>낭비 작업</i>을 막고, 두 번째 요청은 첫 주문을 그대로 반환(멱등).</li>
 *   <li><b>DB UNIQUE 백스톱</b>({@link OrderIdempotencyPort}): 락이 비활성/만료돼 동시 중복이 빠져나가도,
 *       멱등 레코드 INSERT 의 PK 위반이 두 번째 주문 트랜잭션을 통째로 롤백 → 최종 1건만 남는다.</li>
 * </ol>
 *
 * <p>락 획득 → 트랜잭션(멱등 조회 → 주문 생성 → 멱등 기록) → 커밋 → 락 해제 순서로, 락을 트랜잭션
 * 바깥에서 잡아 보유 시간을 짧게 유지한다. 멱등 기록 INSERT 는 주문 생성과 <b>같은 트랜잭션</b>이라
 * 원자적이다. Idempotency-Key 가 없으면 기존 생성 흐름을 그대로 사용한다(하위 호환).
 */
@Slf4j
@Service
public class IdempotentMultiItemOrderService implements IdempotentMultiItemOrderUseCase {

    private static final Duration LOCK_WAIT = Duration.ofSeconds(3);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(10);
    private static final String LOCK_NAMESPACE = "order:create:";

    private final CreateMultiItemOrderUseCase delegate;
    private final DistributedLockPort lockPort;
    private final OrderIdempotencyPort idempotencyPort;
    private final LoadOrderPort loadOrderPort;
    private final TransactionTemplate transactionTemplate;

    public IdempotentMultiItemOrderService(CreateMultiItemOrderUseCase delegate,
                                           DistributedLockPort lockPort,
                                           OrderIdempotencyPort idempotencyPort,
                                           LoadOrderPort loadOrderPort,
                                           TransactionTemplate transactionTemplate) {
        this.delegate = delegate;
        this.lockPort = lockPort;
        this.idempotencyPort = idempotencyPort;
        this.loadOrderPort = loadOrderPort;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public Order create(Long userId, List<CreateMultiItemOrderUseCase.Line> lines,
                        String couponCode, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return delegate.create(userId, lines, couponCode); // 키 없으면 기존 동작(하위 호환)
        }

        return lockPort.executeWithLock(LOCK_NAMESPACE + idempotencyKey, LOCK_WAIT, LOCK_LEASE, () -> {
            try {
                return transactionTemplate.execute(status -> {
                    Optional<Long> existing = idempotencyPort.findOrderId(idempotencyKey);
                    if (existing.isPresent()) {
                        log.info("멱등 주문 replay: key={}, orderId={}", idempotencyKey, existing.get());
                        return loadOrderPort.findById(existing.get())
                                .orElseThrow(() -> new OrderNotFoundException(existing.get()));
                    }
                    Order created = delegate.create(userId, lines, couponCode);
                    idempotencyPort.save(idempotencyKey, created.getId()); // dup 키면 제약 위반 → 트랜잭션 롤백
                    return created;
                });
            } catch (DataIntegrityViolationException dup) {
                // 락 우회 동시 중복 — 승자 주문으로 멱등 복원(별도 읽기). 아직 미커밋이면 재시도 유도.
                log.warn("멱등 키 동시 충돌 — 복원 시도: key={}", idempotencyKey);
                return idempotencyPort.findOrderId(idempotencyKey)
                        .flatMap(loadOrderPort::findById)
                        .orElseThrow(() -> new DuplicateOrderSubmissionException(idempotencyKey));
            }
        });
    }
}
