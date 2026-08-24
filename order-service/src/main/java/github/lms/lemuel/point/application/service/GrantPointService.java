package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.GrantPointUseCase;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 포인트 적립·충전 — 로트를 발급하고 잔고를 늘린다.
 *
 * <p>발급 순서가 중요하다: <b>로트를 먼저 저장해 식별자를 얻은 뒤</b> 원장 엔트리의 배분에
 * 그 식별자를 적는다. 반대로 하면 "어느 로트로 적립됐는지 모르는 엔트리"가 남는다.
 *
 * <p>출처가 현금 충전 원금이면 {@code point.charged}(DR CASH / CR POINT_LIABILITY),
 * 그 밖의 판촉성 적립이면 {@code point.granted}(DR POINT_PROMOTION_EXPENSE / CR POINT_LIABILITY)를
 * 발행한다 — 회계 계정이 다르므로 이벤트도 갈라야 한다.
 */
@Service
@Transactional
public class GrantPointService implements GrantPointUseCase {

    private static final Logger log = LoggerFactory.getLogger(GrantPointService.class);

    private final PointAccountPort accountPort;
    private final PointLotPort lotPort;
    private final PointEntryPort entryPort;
    private final PublishPointEventPort eventPort;

    public GrantPointService(PointAccountPort accountPort, PointLotPort lotPort,
                             PointEntryPort entryPort, PublishPointEventPort eventPort) {
        this.accountPort = accountPort;
        this.lotPort = lotPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public GrantPointResult grant(GrantPointCommand command) {
        PointAccount opened = accountPort.openIfAbsent(command.userId());
        PointAccount account = accountPort.loadForUpdate(command.userId()).orElse(opened);

        int sequence = entryPort.nextSequence(account.getId(), PointEntryType.GRANT,
                command.referenceType(), command.referenceId());
        if (entryPort.existsByReference(account.getId(), PointEntryType.GRANT,
                command.referenceType(), command.referenceId())) {
            log.info("포인트 적립 멱등 단축 반환: userId={}, ref={}:{}",
                    command.userId(), command.referenceType(), command.referenceId());
            return new GrantPointResult(null, null, command.amount(), account.getAvailable());
        }

        account.grant(command.amount());

        PointLot lot = PointLot.issue(account.getId(), command.origin(), command.amount(),
                OffsetDateTime.now(), command.expiresAt(), command.referenceType(), command.referenceId());
        PointLot savedLot = lotPort.save(lot);

        PointEntry entry = PointEntry.grant(account.getId(), command.amount(),
                command.referenceType(), command.referenceId(), sequence,
                List.of(new PointLotConsumption(savedLot.getId(), command.amount())),
                command.actor(), command.memo());

        PointAccount savedAccount = accountPort.save(account);
        PointEntry appended = entryPort.append(entry);

        if (command.origin() == PointLotOrigin.CHARGE_PRINCIPAL) {
            eventPort.pointCharged(savedAccount, savedLot, command.referenceId());
        } else {
            eventPort.pointGranted(savedAccount, savedLot);
        }

        log.info("포인트 적립: userId={}, amount={}, origin={}, 잔액={}",
                command.userId(), command.amount(), command.origin(), savedAccount.getAvailable());
        return new GrantPointResult(appended.getId(), savedLot.getId(),
                command.amount(), savedAccount.getAvailable());
    }
}
