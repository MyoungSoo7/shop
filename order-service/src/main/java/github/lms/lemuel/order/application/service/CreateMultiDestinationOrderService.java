package github.lms.lemuel.order.application.service;

import github.lms.lemuel.order.application.port.in.CreateMultiDestinationOrderUseCase;
import github.lms.lemuel.order.application.port.in.CreateMultiItemOrderUseCase;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 여러 곳 배송 — 배송지마다 주문을 하나씩 만들고 묶음 id 로 묶는다.
 *
 * <p>스스로 주문을 만들지 않는다. 배송지 하나가 곧 평범한 다건 주문 하나이므로
 * {@link CreateMultiItemOrderUseCase} 를 배송지 수만큼 부른다. 그래야 배송비 산정·재고 차감·
 * 동의 기록·배송 생성·이벤트 발행이 단일 배송지 주문과 <b>같은 코드</b>를 지나간다 — 여기서
 * 따로 조립하면 그 규칙들이 두 벌이 되고, 둘이 갈라진 사실은 한참 뒤에 드러난다.
 *
 * <p>전부 한 트랜잭션이다({@link TransactionTemplate}). 세 곳 중 한 곳이 품절이면 앞의 두
 * 주문과 그 재고 차감까지 되돌아간다.
 */
@Slf4j
@Service
public class CreateMultiDestinationOrderService implements CreateMultiDestinationOrderUseCase {

    // 단건 주문(order:create:)보다 길다. 배송지 수만큼의 주문 생성이 한 임계 구역 안에서
    // 일어나므로 같은 10 초로 두면 배송지가 많은 요청에서 락이 먼저 만료된다.
    private static final Duration LOCK_WAIT = Duration.ofSeconds(5);
    private static final Duration LOCK_LEASE = Duration.ofSeconds(30);
    private static final String LOCK_NAMESPACE = "order:multi-destination:";

    private final CreateMultiItemOrderUseCase delegate;
    private final DistributedLockPort lockPort;
    private final OrderIdempotencyPort idempotencyPort;
    private final LoadOrderPort loadOrderPort;
    private final TransactionTemplate transactionTemplate;

    public CreateMultiDestinationOrderService(CreateMultiItemOrderUseCase delegate,
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
    public Result create(Command command) {
        String key = command.idempotencyKey();
        if (key == null || key.isBlank()) {
            // 키 없는 호출도 한 트랜잭션은 지킨다 — 멱등 보호가 없다는 것과 부분 성공을 남긴다는 것은 다르다.
            return transactionTemplate.execute(status -> fanOut(command));
        }

        return lockPort.executeWithLock(LOCK_NAMESPACE + key, LOCK_WAIT, LOCK_LEASE, () -> {
            try {
                return transactionTemplate.execute(status -> {
                    Optional<Long> existing = idempotencyPort.findOrderId(key);
                    if (existing.isPresent()) {
                        log.info("여러 곳 배송 멱등 replay: key={}, anchorOrderId={}", key, existing.get());
                        return replay(key, existing.get());
                    }
                    Result created = fanOut(command);
                    // 멱등 원장은 키 하나에 주문 id 하나만 담는다. 묶음의 첫 주문을 닻으로 적고,
                    // 재요청은 그 주문이 들고 있는 묶음 id 로 나머지를 되찾는다.
                    idempotencyPort.save(key, created.orders().get(0).getId());
                    return created;
                });
            } catch (DataIntegrityViolationException dup) {
                log.warn("여러 곳 배송 멱등 키 동시 충돌 — 복원 시도: key={}", key);
                return idempotencyPort.findOrderId(key)
                        .map(anchorId -> replay(key, anchorId))
                        .orElseThrow(() -> new DuplicateOrderSubmissionException(key));
            }
        });
    }

    /**
     * 배송지 하나당 주문 하나. 순서는 요청의 배송지 순서 그대로다.
     *
     * <p>쿠폰은 받지 않는다({@code couponCode = null}). 쿠폰의 최소 주문금액과 1 인 사용 한도는
     * <b>주문 한 건</b>에 걸리는 조건이라, 한 장을 N 건에 나누려면 "어느 주문이 얼마를 가져가는가"
     * 라는 배분 규칙이 먼저 있어야 한다. 그 규칙 없이 통과시키면 같은 쿠폰이 N 번 쓰이거나
     * (한도 무력화) 첫 주문에서만 쓰이고 나머지는 조용히 정가가 된다.
     */
    private Result fanOut(Command command) {
        String groupId = UUID.randomUUID().toString();
        List<Order> orders = new ArrayList<>(command.destinations().size());
        for (Destination destination : command.destinations()) {
            orders.add(delegate.create(
                    command.userId(),
                    destination.lines(),
                    null,
                    destination.shippingAddress(),
                    command.consent(),
                    groupId));
        }
        log.info("여러 곳 배송 생성 완료: userId={}, 배송지={}곳, groupId={}",
                command.userId(), orders.size(), groupId);
        return new Result(groupId, List.copyOf(orders));
    }

    /**
     * 닻 주문 하나에서 묶음 전체를 되살린다.
     *
     * <p>닻에 묶음 id 가 없다면 그 키는 여러 곳 배송이 아닌 주문이 이미 써 버린 키다. 그 주문
     * 한 건을 "여러 곳 배송 결과" 라고 돌려주면 화면은 배송지 하나짜리 묶음을 그리게 되므로,
     * 중복 제출로 거절한다.
     */
    private Result replay(String key, Long anchorOrderId) {
        Order anchor = loadOrderPort.findById(anchorOrderId)
                .orElseThrow(() -> new OrderNotFoundException(anchorOrderId));
        String groupId = anchor.getDestinationGroupId();
        if (groupId == null) {
            log.warn("멱등 키가 여러 곳 배송이 아닌 주문에 이미 쓰였다: key={}, orderId={}", key, anchorOrderId);
            throw new DuplicateOrderSubmissionException(key);
        }
        List<Order> orders = loadOrderPort.findByDestinationGroupId(groupId);
        // 닻은 조회됐는데 묶음이 비어 나오는 경우는 없다(닻 자신이 그 묶음이다). 그래도 빈 목록을
        // 그대로 돌려주면 "주문이 하나도 없는 성공" 이 되므로 닻만이라도 세워 둔다.
        return new Result(groupId, orders.isEmpty() ? List.of(anchor) : orders);
    }
}
