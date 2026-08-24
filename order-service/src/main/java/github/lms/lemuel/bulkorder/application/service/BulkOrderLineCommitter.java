package github.lms.lemuel.bulkorder.application.service;

import github.lms.lemuel.bulkorder.application.port.out.PlaceBulkOrderLinePort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 대량주문 확정의 <b>행 단위 트랜잭션 경계</b>.
 *
 * <p>왜 별도 빈인가: 확정은 수백 행을 한 번에 처리하는 작업이라, 뒷쪽 한 행의 실패가 앞쪽 전부를
 * 롤백하면 안 된다. 롤백된 재고를 그 사이 다른 주문이 가져가면 재시도는 같은 결과를 내지 못하고,
 * 운영자는 "몇 건이 나갔는지"조차 알 수 없게 된다.
 *
 * <p>{@code REQUIRES_NEW} 를 같은 클래스의 메서드에 달고 self-invocation 으로 부르면 프록시를
 * 거치지 않아 트랜잭션이 적용되지 않는다 — 그래서 반드시 별도 빈이어야 한다.
 */
@Component
public class BulkOrderLineCommitter {

    private final PlaceBulkOrderLinePort placeBulkOrderLinePort;

    public BulkOrderLineCommitter(PlaceBulkOrderLinePort placeBulkOrderLinePort) {
        this.placeBulkOrderLinePort = placeBulkOrderLinePort;
    }

    /** 행 1줄을 실주문으로 확정한다. 실패하면 이 행만 롤백되고 예외가 호출자에게 올라간다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long commit(Long buyerUserId, PlaceBulkOrderLinePort.Line line) {
        return placeBulkOrderLinePort.place(buyerUserId, line);
    }
}
