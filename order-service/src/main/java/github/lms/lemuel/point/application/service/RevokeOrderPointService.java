package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.RevokeOrderPointUseCase;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotConsumption;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 주문 적립 회수 — 주문이 취소·환불되면 그 주문으로 준 적립분을 되가져온다.
 *
 * <p><b>이미 써 버린 적립분은 회수하지 않는다.</b> 잔고를 음수로 만들거나 다른 적립분에서 빼 오면
 * 고객이 정당하게 가진 포인트를 건드리게 된다. 회수 가능한 몫(로트에 남은 잔량)만 되가져오고,
 * 나머지는 회사 손실로 남긴다 — 그게 실제 회계이고, 원장에도 그렇게 적힌다.
 */
@Service
@Transactional
public class RevokeOrderPointService implements RevokeOrderPointUseCase {

    private static final Logger log = LoggerFactory.getLogger(RevokeOrderPointService.class);

    /** 적립 로트·엔트리의 참조 종류 — {@link EarnPointOnOrderService} 와 같은 값이어야 찾을 수 있다. */
    private static final String REFERENCE_TYPE = "ORDER";

    private final PointAccountPort accountPort;
    private final PointLotPort lotPort;
    private final PointEntryPort entryPort;
    private final PublishPointEventPort eventPort;

    public RevokeOrderPointService(PointAccountPort accountPort, PointLotPort lotPort,
                                   PointEntryPort entryPort, PublishPointEventPort eventPort) {
        this.accountPort = accountPort;
        this.lotPort = lotPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public RevokeOrderPointResult revoke(RevokeOrderPointCommand command) {
        String orderRef = String.valueOf(command.orderId());

        Optional<Long> accountId = entryPort.findAccountIdByReference(
                PointEntryType.GRANT, REFERENCE_TYPE, orderRef);
        if (accountId.isEmpty()) {
            // 적립이 애초에 없었다(정책 미설정·1원 미만·미확정 주문). 정상 경로다.
            return new RevokeOrderPointResult(BigDecimal.ZERO);
        }

        PointAccount account = accountPort.loadByIdForUpdate(accountId.get())
                .orElseThrow(() -> new PointInvariantViolationException(
                        "적립 엔트리가 가리키는 계정이 없습니다: accountId=" + accountId.get()));

        int sequence = entryPort.nextSequence(account.getId(), PointEntryType.REVOKE,
                REFERENCE_TYPE, orderRef);
        if (entryPort.existsByReference(account.getId(), PointEntryType.REVOKE, REFERENCE_TYPE, orderRef)) {
            log.info("적립 회수 멱등 단축 반환: orderId={}", command.orderId());
            return new RevokeOrderPointResult(BigDecimal.ZERO);
        }

        List<PointLot> lots = lotPort.loadByIds(grantedLotIds(account.getId(), orderRef));
        List<PointLotConsumption> allocations = new ArrayList<>();
        List<PointLot> revokedLots = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (PointLot lot : lots) {
            if (!lot.getStatus().isConsumable()) {
                // 이미 소진·소멸·취소된 로트 — 되가져올 잔량이 없다.
                continue;
            }
            BigDecimal revoked = lot.revoke();
            revokedLots.add(lot);
            if (revoked.signum() > 0) {
                allocations.add(new PointLotConsumption(lot.getId(), revoked));
                total = total.add(revoked);
            }
        }

        if (!revokedLots.isEmpty()) {
            lotPort.saveAll(revokedLots);
        }
        if (total.signum() == 0) {
            // 로트는 닫았지만 되가져온 금액이 없다 — 0원 엔트리는 만들지 않는다.
            log.info("적립 회수: 되가져올 잔량 없음(전액 사용·소멸). orderId={}", command.orderId());
            accountPort.save(account);
            return new RevokeOrderPointResult(BigDecimal.ZERO);
        }

        account.revoke(total);
        PointEntry entry = PointEntry.revoke(account.getId(), total, REFERENCE_TYPE, orderRef,
                sequence, allocations, command.actor());

        PointAccount savedAccount = accountPort.save(account);
        PointEntry appended = entryPort.append(entry);
        eventPort.pointRevoked(savedAccount, appended);

        log.info("적립 회수: orderId={}, 회수={}, 잔액={}",
                command.orderId(), total, savedAccount.getAvailable());
        return new RevokeOrderPointResult(total);
    }

    private List<Long> grantedLotIds(Long accountId, String orderRef) {
        return entryPort.loadByReference(accountId, PointEntryType.GRANT, REFERENCE_TYPE, orderRef)
                .stream()
                .flatMap(entry -> entry.getAllocations().stream())
                .map(PointLotConsumption::lotId)
                .distinct()
                .toList();
    }
}
