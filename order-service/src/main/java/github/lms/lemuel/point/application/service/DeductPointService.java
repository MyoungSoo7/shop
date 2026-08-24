package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.DeductPointUseCase;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotConsumption;
import github.lms.lemuel.point.domain.PointLotSelector;
import github.lms.lemuel.point.domain.exception.InsufficientPointException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * 수기 차감 — 운영자가 오지급·부정 적립을 거둬들이는 경로. 수기 지급의 역방향이다.
 *
 * <p>순서는 사용(UsePointService)과 같다. 같아야 하기 때문이다 — 둘 다 잔고를 줄이므로,
 * 한쪽만 다른 순서를 쓰면 그 차이가 곧 경합 창이 된다.
 *
 * <ol>
 *   <li><b>비관적 락</b>으로 계정을 잡는다. 회수와 결제가 겹치면 같은 포인트가 두 번 빠진다.
 *   <li><b>멱등 단축 반환</b> — 같은 참조로 이미 차감했다면 아무것도 하지 않는다. 운영자가
 *       응답을 못 보고 다시 누르는 일은 실제로 일어나고, 그때 두 번 빠지면 고객 돈이 사라진다.
 *   <li>잔고를 먼저 줄여 <b>상태·잔액을 판정</b>한 뒤 로트를 소비한다.
 *   <li>계정·로트·원장을 같은 트랜잭션에서 저장한다 — 잔고만 줄고 로트가 남으면 3자 대조가 즉시 깨진다.
 * </ol>
 *
 * <p>이벤트는 {@code pointRevoked} 를 그대로 쓴다. 소비자 입장에서 "회수됐다"는 사실은 같고,
 * 주문 취소분인지 수기 회수인지는 엔트리의 {@code referenceType} 이 말한다.
 *
 * <p>로트 소비 순서는 <b>만료 임박 순</b>(사용과 동일)이다. 회수 대상 로트를 특정하지 않는 이유는,
 * 운영자가 "얼마를" 은 알아도 "어느 적립 건에서" 는 대개 모르기 때문이다. 특정 적립 건을 통째로
 * 되돌리는 것은 주문 회수({@code RevokeOrderPointUseCase})의 몫이다.
 */
@Service
@Transactional
public class DeductPointService implements DeductPointUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeductPointService.class);

    /** 수기 경로의 참조 종류 — 수기 지급과 같은 값이라 계정 상세에서 한 묶음으로 읽힌다. */
    private static final String REFERENCE_TYPE = "MANUAL";

    private final PointAccountPort accountPort;
    private final PointLotPort lotPort;
    private final PointEntryPort entryPort;
    private final PublishPointEventPort eventPort;

    public DeductPointService(PointAccountPort accountPort, PointLotPort lotPort,
                              PointEntryPort entryPort, PublishPointEventPort eventPort) {
        this.accountPort = accountPort;
        this.lotPort = lotPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public DeductPointResult deduct(DeductPointCommand command) {
        PointAccount account = accountPort.loadForUpdate(command.userId())
                .orElseThrow(() -> new InsufficientPointException(
                        "포인트 계정이 없습니다: userId=" + command.userId(),
                        command.amount(), BigDecimal.ZERO));

        int sequence = entryPort.nextSequence(account.getId(), PointEntryType.REVOKE,
                REFERENCE_TYPE, command.referenceId());

        if (entryPort.existsByReference(account.getId(), PointEntryType.REVOKE,
                REFERENCE_TYPE, command.referenceId())) {
            log.info("수기 차감 멱등 단축 반환: userId={}, ref={}", command.userId(), command.referenceId());
            return new DeductPointResult(null, command.amount(), account.getAvailable());
        }

        // 잔고를 먼저 줄여 상태(해지)와 잔액 부족을 판정한다 — 로트를 건드린 뒤에 거절하면
        // 이미 소비된 로트를 되돌려야 한다.
        account.deduct(command.amount());

        List<PointLot> lots = lotPort.loadConsumable(account.getId());
        List<PointLotConsumption> allocations = PointLotSelector.consume(lots, command.amount());

        PointEntry entry = PointEntry.manualDeduct(account.getId(), command.amount(),
                command.referenceId(), sequence, allocations, command.actor(), command.reason());

        PointAccount saved = accountPort.save(account);
        lotPort.saveAll(lots);
        PointEntry appended = entryPort.append(entry);
        eventPort.pointRevoked(saved, appended);

        log.info("수기 차감: userId={}, amount={}, 잔액={}, 사유={}, by={}",
                command.userId(), command.amount(), saved.getAvailable(),
                command.reason(), command.actor());
        return new DeductPointResult(appended.getId(), command.amount(), saved.getAvailable());
    }
}
