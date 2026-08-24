package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.ExpirePointLotsUseCase;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 포인트 소멸 배치 — 유효기간이 지난 로트를 닫고 잔고를 차감한다.
 *
 * <p>고객 재산을 지우는 작업이라 {@code dryRun} 으로 먼저 규모를 확인할 수 있게 한다
 * (ofDentis 레거시의 대량 작업 교훈, P0-3 dry-run 프로토콜).
 *
 * <p>로트를 계정별로 묶어 계정당 한 번만 락을 잡는다. 로트 하나마다 락을 잡으면 같은 계정에
 * 로트가 여러 개일 때 락을 반복 획득해 경합이 커진다.
 */
@Service
@Transactional
public class ExpirePointLotsService implements ExpirePointLotsUseCase {

    private static final Logger log = LoggerFactory.getLogger(ExpirePointLotsService.class);

    /** 소멸 엔트리의 참조 종류 — 로트 하나가 근거이므로 로트 식별자를 참조 id 로 쓴다. */
    private static final String REFERENCE_TYPE = "LOT_EXPIRY";

    private final PointAccountPort accountPort;
    private final PointLotPort lotPort;
    private final PointEntryPort entryPort;
    private final PublishPointEventPort eventPort;

    public ExpirePointLotsService(PointAccountPort accountPort, PointLotPort lotPort,
                                  PointEntryPort entryPort, PublishPointEventPort eventPort) {
        this.accountPort = accountPort;
        this.lotPort = lotPort;
        this.entryPort = entryPort;
        this.eventPort = eventPort;
    }

    @Override
    public ExpirePointResult expire(ExpirePointCommand command) {
        List<PointLot> expired = lotPort.loadExpired(command.at(), command.batchSize());
        if (expired.isEmpty()) {
            return new ExpirePointResult(0, 0, BigDecimal.ZERO, command.dryRun());
        }

        Map<Long, List<PointLot>> byAccount = new LinkedHashMap<>();
        for (PointLot lot : expired) {
            byAccount.computeIfAbsent(lot.getAccountId(), key -> new ArrayList<>()).add(lot);
        }

        if (command.dryRun()) {
            BigDecimal preview = expired.stream()
                    .map(PointLot::getRemainingAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            log.info("포인트 소멸 dry-run: lots={}, accounts={}, 소멸예정={}",
                    expired.size(), byAccount.size(), preview);
            return new ExpirePointResult(expired.size(), byAccount.size(), preview, true);
        }

        BigDecimal forfeitedTotal = BigDecimal.ZERO;
        int lotCount = 0;
        for (Map.Entry<Long, List<PointLot>> group : byAccount.entrySet()) {
            forfeitedTotal = forfeitedTotal.add(expireForAccount(group.getKey(), group.getValue(), command));
            lotCount += group.getValue().size();
        }

        log.info("포인트 소멸 완료: lots={}, accounts={}, 소멸액={}",
                lotCount, byAccount.size(), forfeitedTotal);
        return new ExpirePointResult(lotCount, byAccount.size(), forfeitedTotal, false);
    }

    private BigDecimal expireForAccount(Long accountId, List<PointLot> lots, ExpirePointCommand command) {
        PointAccount account = accountPort.loadByIdForUpdate(accountId)
                .orElseThrow(() -> new PointInvariantViolationException(
                        "로트가 가리키는 계정이 없습니다: accountId=" + accountId));

        BigDecimal accountTotal = BigDecimal.ZERO;
        List<PointLot> closed = new ArrayList<>(lots.size());
        for (PointLot lot : lots) {
            BigDecimal forfeited = lot.expire(command.at());
            if (forfeited.signum() == 0) {
                // 잔량 0 인 로트는 잔고에 영향이 없다. 0원 엔트리를 만들지 않는다.
                closed.add(lot);
                continue;
            }
            account.expire(forfeited);
            accountTotal = accountTotal.add(forfeited);
            closed.add(lot);

            int sequence = entryPort.nextSequence(accountId, PointEntryType.EXPIRE,
                    REFERENCE_TYPE, String.valueOf(lot.getId()));
            PointEntry entry = PointEntry.expire(accountId, forfeited, REFERENCE_TYPE,
                    String.valueOf(lot.getId()), sequence,
                    List.of(new PointLotConsumption(lot.getId(), forfeited)), command.actor());
            entryPort.append(entry);
            eventPort.pointExpired(account, lot, forfeited);
        }

        lotPort.saveAll(closed);
        accountPort.save(account);
        return accountTotal;
    }
}
