package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotConsumption;
import github.lms.lemuel.point.domain.PointLotSelector;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * "포인트를 실제로 썼다"를 장부에 적는 공통 절차 — 로트 소비(만료 임박 순) + USE 엔트리 + 이벤트.
 *
 * <p><b>왜 뽑았나</b>: 이 절차를 부르는 경로가 둘이다.
 *
 * <ul>
 *   <li>{@link UsePointService} — 즉시 결제. 가용에서 바로 뺀다({@code account.use}).
 *   <li>{@link HoldPointService} — 입금 대기 결제의 <b>확정</b>. 이미 잠가 둔 몫을 쓴다
 *       ({@code account.captureHold}). 가용은 선점 시점에 이미 줄어 있어 {@code use} 를 부르면
 *       잔액 부족으로 거절당한다 — 그래서 확정은 {@code use} 를 재사용할 수 없다.
 * </ul>
 *
 * <p>두 경로가 <b>잔고를 줄이는 방법만</b> 다르고 그 뒤는 같다. 같은 절차를 두 벌 두면 소비 순서나
 * 엔트리 규약이 한쪽에서만 바뀌는 날이 오고, 그때 3자 대조가 어긋난다.
 *
 * <p>계정 잔고 변경은 <b>호출자가 먼저</b> 끝낸 뒤 이 절차를 부른다 — 상태 규칙(정지 계정)과
 * 잔액 판정을 로트를 건드리기 전에 통과해야 하기 때문이다.
 *
 * <p>트랜잭션을 스스로 열지 않는다. 호출자의 트랜잭션 안에서 계정·로트·원장이 함께 커밋돼야 한다.
 */
@Component
public class PointSpendRecorder {

    private final PointAccountPort accountPort;
    private final PointLotPort lotPort;
    private final PointEntryPort entryPort;
    private final PublishPointEventPort eventPort;

    public PointSpendRecorder(PointAccountPort accountPort, PointLotPort lotPort,
                              PointEntryPort entryPort, PublishPointEventPort eventPort) {
        this.accountPort = accountPort;
        this.lotPort = lotPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    /** 같은 근거로 이미 사용이 기록됐는가 — 호출자가 멱등 단축 반환을 판정할 때 쓴다. */
    public boolean alreadyRecorded(Long accountId, String referenceType, String referenceId) {
        return entryPort.existsByReference(accountId, PointEntryType.USE, referenceType, referenceId);
    }

    /**
     * 잔고가 이미 줄어든 계정에 대해 로트 소비와 원장 기록을 마친다.
     *
     * @return 기록된 엔트리
     */
    public PointEntry record(PointAccount account, BigDecimal amount,
                             String referenceType, String referenceId, String actor) {
        int sequence = entryPort.nextSequence(account.getId(), PointEntryType.USE,
                referenceType, referenceId);

        List<PointLot> lots = lotPort.loadConsumable(account.getId());
        List<PointLotConsumption> allocations = PointLotSelector.consume(lots, amount);

        PointEntry entry = PointEntry.use(account.getId(), amount,
                referenceType, referenceId, sequence, allocations, actor);

        PointAccount saved = accountPort.save(account);
        lotPort.saveAll(lots);
        PointEntry appended = entryPort.append(entry);
        eventPort.pointUsed(saved, appended);
        return appended;
    }
}
