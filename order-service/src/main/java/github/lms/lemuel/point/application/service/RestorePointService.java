package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.RestorePointUseCase;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotConsumption;
import github.lms.lemuel.point.domain.PointLotOrigin;
import github.lms.lemuel.point.domain.PointLotStatus;
import github.lms.lemuel.point.domain.exception.InvalidPointStateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 포인트 환불 복원 — 사용의 대칭 연산.
 *
 * <p><b>되돌리는 순서는 역순(LIFO)이다.</b> 사용은 만료 임박분부터 먹으므로, 되돌릴 때 마지막에
 * 쓴 로트부터 돌려주면 고객은 유효기간이 더 긴 포인트를 돌려받는다. 반대로 하면 곧 소멸할
 * 포인트를 돌려주게 되어 실질적으로 덜 돌려주는 셈이 된다.
 *
 * <p>원 로트가 아직 살아 있으면({@code ACTIVE}/{@code EXHAUSTED}) 그 로트로 되돌린다 — 고객이
 * 쓰지 않았더라면 그 로트는 여전히 원래 만료일을 가졌을 것이므로 이쪽이 정확하다. 이미
 * <b>소멸·취소</b>된 로트는 되살리지 않고 {@link PointLotOrigin#REFUND_RESTORE} 로 새 로트를
 * 발급하되, 유효기간은 <b>원 로트가 가졌던 기간과 같은 길이</b>를 지금부터 적용한다(무기한이었다면
 * 무기한). 기본 정책값을 쓰면 원래보다 긴 유효기간을 덤으로 주게 된다.
 *
 * <p>총액 상한은 두 겹으로 보장된다: 결제 도메인이 초과 환불을 막고
 * ({@code PaymentTender.addRefund}), 로트가 원 발급액 초과 복원을 막는다.
 */
@Service
@Transactional
public class RestorePointService implements RestorePointUseCase {

    private static final Logger log = LoggerFactory.getLogger(RestorePointService.class);

    private final PointAccountPort accountPort;
    private final PointLotPort lotPort;
    private final PointEntryPort entryPort;
    private final PublishPointEventPort eventPort;

    public RestorePointService(PointAccountPort accountPort, PointLotPort lotPort,
                               PointEntryPort entryPort, PublishPointEventPort eventPort) {
        this.accountPort = accountPort;
        this.lotPort = lotPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public RestorePointResult restore(RestorePointCommand command) {
        // 복원 대상 계정은 원 사용 엔트리에서 도출한다 — 호출자가 알려 준 식별자를 믿지 않는다.
        Long accountId = entryPort.findAccountIdByReference(PointEntryType.USE,
                        command.sourceReferenceType(), command.sourceReferenceId())
                .orElseThrow(() -> new InvalidPointStateException(
                        "복원 근거가 되는 사용 이력이 없습니다: " + command.sourceReferenceType()
                                + ":" + command.sourceReferenceId(), "NONE", "restore"));
        PointAccount account = accountPort.loadByIdForUpdate(accountId)
                .orElseThrow(() -> new InvalidPointStateException(
                        "복원할 포인트 계정이 없습니다: accountId=" + accountId, "NONE", "restore"));

        int sequence = entryPort.nextSequence(account.getId(), PointEntryType.RESTORE,
                command.referenceType(), command.referenceId());
        if (entryPort.existsByReference(account.getId(), PointEntryType.RESTORE,
                command.referenceType(), command.referenceId())) {
            log.info("포인트 복원 멱등 단축 반환: accountId={}, ref={}:{}",
                    account.getId(), command.referenceType(), command.referenceId());
            return new RestorePointResult(null, command.amount(), account.getAvailable());
        }

        List<PointLotConsumption> plan = planRestore(account.getId(), command);
        List<PointLotConsumption> allocations = applyRestore(account, plan);

        account.restore(command.amount());

        PointEntry entry = PointEntry.restore(account.getId(), command.amount(),
                command.referenceType(), command.referenceId(), sequence, allocations, command.actor());

        PointAccount savedAccount = accountPort.save(account);
        PointEntry appended = entryPort.append(entry);
        eventPort.pointRestored(savedAccount, appended);

        log.info("포인트 복원: accountId={}, amount={}, 잔액={}, lots={}",
                savedAccount.getId(), command.amount(), savedAccount.getAvailable(), allocations.size());
        return new RestorePointResult(appended.getId(), command.amount(), savedAccount.getAvailable());
    }

    /**
     * 원 사용 엔트리의 로트 배분을 역순으로 훑어 이번 복원액을 나눈다.
     *
     * <p>같은 tender 를 여러 번 부분 환불하면 매번 역순 끝에서 다시 시작한다. 분배가 최초 소비와
     * 1:1 로 대응하지는 않지만, 각 로트가 원 발급액을 넘지 못하고 총액은 결제 도메인이 막으므로
     * 금액은 보존된다.
     */
    private List<PointLotConsumption> planRestore(Long accountId, RestorePointCommand command) {
        List<PointEntry> sourceEntries = entryPort.loadByReference(accountId, PointEntryType.USE,
                command.sourceReferenceType(), command.sourceReferenceId());
        if (sourceEntries.isEmpty()) {
            throw new InvalidPointStateException(
                    "복원 근거가 되는 사용 엔트리를 읽지 못했습니다: " + command.sourceReferenceType()
                            + ":" + command.sourceReferenceId(), "NONE", "restore");
        }

        // 로트별 소비 합계(소비 순서 보존).
        Map<Long, BigDecimal> consumedByLot = new LinkedHashMap<>();
        for (PointEntry entry : sourceEntries) {
            for (PointLotConsumption allocation : entry.getAllocations()) {
                consumedByLot.merge(allocation.lotId(), allocation.amount(), BigDecimal::add);
            }
        }

        List<Long> reverseOrder = new ArrayList<>(consumedByLot.keySet());
        java.util.Collections.reverse(reverseOrder);

        List<PointLotConsumption> plan = new ArrayList<>();
        BigDecimal remaining = command.amount();
        for (Long lotId : reverseOrder) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal take = consumedByLot.get(lotId).min(remaining);
            plan.add(new PointLotConsumption(lotId, take));
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) {
            throw new InvalidPointStateException(
                    "복원 요청이 원 사용액을 초과합니다: 초과분 " + remaining, "NONE", "restore");
        }
        return plan;
    }

    /** 계획을 실제 로트에 적용한다. 소멸·취소된 로트는 새 로트로 대체하고 그 식별자를 배분에 적는다. */
    private List<PointLotConsumption> applyRestore(PointAccount account, List<PointLotConsumption> plan) {
        List<Long> lotIds = plan.stream().map(PointLotConsumption::lotId).toList();
        Map<Long, PointLot> lotsById = new LinkedHashMap<>();
        for (PointLot lot : lotPort.loadByIds(lotIds)) {
            lotsById.put(lot.getId(), lot);
        }

        List<PointLotConsumption> allocations = new ArrayList<>(plan.size());
        List<PointLot> revived = new ArrayList<>();
        for (PointLotConsumption item : plan) {
            PointLot origin = lotsById.get(item.lotId());
            if (origin == null) {
                throw new InvalidPointStateException(
                        "복원 대상 로트를 찾을 수 없습니다: lotId=" + item.lotId(), "NONE", "restore");
            }
            if (origin.getStatus() == PointLotStatus.ACTIVE
                    || origin.getStatus() == PointLotStatus.EXHAUSTED) {
                origin.restoreConsumed(item.amount());
                revived.add(origin);
                allocations.add(item);
            } else {
                PointLot replacement = issueReplacement(account.getId(), origin, item.amount());
                PointLot savedReplacement = lotPort.save(replacement);
                allocations.add(new PointLotConsumption(savedReplacement.getId(), item.amount()));
            }
        }
        if (!revived.isEmpty()) {
            lotPort.saveAll(revived);
        }
        return allocations;
    }

    private PointLot issueReplacement(Long accountId, PointLot origin, BigDecimal amount) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime expiresAt = null;
        if (origin.getExpiresAt() != null) {
            // 원 로트가 가졌던 유효기간 길이를 지금부터 다시 준다 — 기본 정책값을 쓰면 덤이 된다.
            Duration originalWindow = Duration.between(origin.getGrantedAt(), origin.getExpiresAt());
            expiresAt = now.plus(originalWindow);
        }
        return PointLot.issue(accountId, PointLotOrigin.REFUND_RESTORE, amount, now, expiresAt,
                "REFUND_RESTORE_OF_LOT", String.valueOf(origin.getId()));
    }
}
