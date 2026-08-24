package github.lms.lemuel.point.application.service;

import github.lms.lemuel.point.application.port.in.GrantPointUseCase.GrantPointCommand;
import github.lms.lemuel.point.application.port.in.UsePointUseCase.UsePointCommand;
import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.application.port.out.PublishPointEventPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointAccountStatus;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotOrigin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 멱등 회귀 테스트 — <b>실기동에서 잡힌 결함</b>의 재발 방지.
 *
 * <p>처음 구현은 멱등 판정을 {@code exists(..., nextSequence())} 로 물었다. {@code nextSequence} 는
 * "다음 번호"(max+1)를 주므로, 두 번째 호출에서는 <b>아직 존재하지 않는 번호</b>로 존재 여부를 묻게 되어
 * 단축 반환이 <b>한 번도 발동하지 않았다</b>. 결과는 두 가지였다:
 *
 * <ul>
 *   <li>적립: 로트 자연키 UNIQUE 가 막아 500 이 났다(돈은 안전했지만 운영자에게는 서버 오류로 보였다).
 *   <li>사용: 원장 자연키에 sequence 가 포함돼 UNIQUE 가 걸리지 않아 <b>두 번 차감됐다</b>.
 * </ul>
 *
 * <p>기존 단위 테스트가 이를 놓친 이유는 {@code nextSequence} 목이 언제나 0 을 돌려줬기 때문이다.
 * 그래서 이 테스트는 <b>실제로 저장된 것을 기억하는 페이크</b>를 쓴다.
 */
class PointIdempotencyRegressionTest {

    private static final Long USER_ID = 42L;
    private static final Long ACCOUNT_ID = 7L;
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-01T00:00:00Z");

    /** 저장한 엔트리를 기억하는 페이크 — 목이 0 을 고정 반환하던 자리를 실제 동작으로 채운다. */
    private static final class FakeEntryPort implements PointEntryPort {
        private final List<PointEntry> stored = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();

        @Override
        public PointEntry append(PointEntry entry) {
            entry.assignId(ids.incrementAndGet());
            stored.add(entry);
            return entry;
        }

        @Override
        public int nextSequence(Long accountId, PointEntryType type, String referenceType, String referenceId) {
            return (int) stored.stream()
                    .filter(e -> matches(e, accountId, type, referenceType, referenceId))
                    .count();
        }

        @Override
        public boolean exists(Long accountId, PointEntryType type, String referenceType,
                              String referenceId, int sequence) {
            return stored.stream().anyMatch(e -> matches(e, accountId, type, referenceType, referenceId)
                    && e.getSequence() == sequence);
        }

        @Override
        public boolean existsByReference(Long accountId, PointEntryType type,
                                         String referenceType, String referenceId) {
            return stored.stream().anyMatch(e -> matches(e, accountId, type, referenceType, referenceId));
        }

        @Override
        public List<PointEntry> loadByReference(Long accountId, PointEntryType type,
                                                String referenceType, String referenceId) {
            return stored.stream()
                    .filter(e -> matches(e, accountId, type, referenceType, referenceId))
                    .toList();
        }

        @Override
        public Optional<Long> findAccountIdByReference(PointEntryType type, String referenceType,
                                                       String referenceId) {
            return stored.stream()
                    .filter(e -> e.getType() == type && e.getReferenceType().equals(referenceType)
                            && e.getReferenceId().equals(referenceId))
                    .map(PointEntry::getAccountId)
                    .findFirst();
        }

        private static boolean matches(PointEntry e, Long accountId, PointEntryType type,
                                       String referenceType, String referenceId) {
            return e.getAccountId().equals(accountId) && e.getType() == type
                    && e.getReferenceType().equals(referenceType) && e.getReferenceId().equals(referenceId);
        }

        int count() {
            return stored.size();
        }
    }

    /** 로트 자연키 UNIQUE 를 흉내 내는 페이크 — 같은 근거로 두 번 발급하면 DB 처럼 거부한다. */
    private static final class FakeLotPort implements PointLotPort {
        private final List<PointLot> stored = new ArrayList<>();
        private final AtomicLong ids = new AtomicLong();

        @Override
        public List<PointLot> loadConsumable(Long accountId) {
            return stored.stream().filter(PointLot::isConsumable).toList();
        }

        @Override
        public List<PointLot> loadByIds(Collection<Long> lotIds) {
            return stored.stream().filter(l -> lotIds.contains(l.getId())).toList();
        }

        @Override
        public List<PointLot> loadExpired(OffsetDateTime at, int limit) {
            return List.of();
        }

        @Override
        public PointLot save(PointLot lot) {
            if (lot.getId() == null) {
                boolean duplicate = stored.stream().anyMatch(l ->
                        l.getAccountId().equals(lot.getAccountId()) && l.getOrigin() == lot.getOrigin()
                                && l.getReferenceType().equals(lot.getReferenceType())
                                && l.getReferenceId().equals(lot.getReferenceId()));
                if (duplicate) {
                    throw new IllegalStateException("uq_point_lots_natural 위반");
                }
                lot.assignId(ids.incrementAndGet());
                stored.add(lot);
            }
            return lot;
        }

        @Override
        public List<PointLot> saveAll(List<PointLot> lots) {
            lots.forEach(this::save);
            return lots;
        }
    }

    private PointAccount account;
    private FakeEntryPort entryPort;
    private FakeLotPort lotPort;
    private PointAccountPort accountPort;
    private PublishPointEventPort eventPort;

    @BeforeEach
    void setUp() {
        account = PointAccount.rehydrate(ACCOUNT_ID, USER_ID, new BigDecimal("10000"),
                BigDecimal.ZERO, new BigDecimal("10000"), PointAccountStatus.ACTIVE, 0L, NOW, NOW);
        entryPort = new FakeEntryPort();
        lotPort = new FakeLotPort();
        eventPort = org.mockito.Mockito.mock(PublishPointEventPort.class);
        accountPort = org.mockito.Mockito.mock(PointAccountPort.class);
        org.mockito.Mockito.when(accountPort.loadForUpdate(USER_ID)).thenReturn(Optional.of(account));
        org.mockito.Mockito.when(accountPort.openIfAbsent(USER_ID)).thenReturn(account);
        org.mockito.Mockito.when(accountPort.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("같은 참조로 두 번 사용해도 한 번만 차감된다 — 이중 차감은 돈이 사라지는 사고다")
    void useIsIdempotentAcrossCalls() {
        lotPort.save(PointLot.issue(ACCOUNT_ID, PointLotOrigin.ORDER_EARN, new BigDecimal("10000"),
                NOW, null, "SEED", "1"));
        UsePointService service = new UsePointService(accountPort,
                new PointSpendRecorder(accountPort, lotPort, entryPort, eventPort));
        UsePointCommand command = new UsePointCommand(
                USER_ID, new BigDecimal("3000"), "PAYMENT_TENDER", "77", "user:42");

        service.use(command);
        service.use(command);

        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("7000"));
        assertThat(entryPort.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 참조로 두 번 지급해도 예외 없이 한 번만 반영된다 — 운영자에게 500 을 보이지 않는다")
    void grantIsIdempotentAcrossCalls() {
        GrantPointService service = new GrantPointService(accountPort, lotPort, entryPort, eventPort);
        GrantPointCommand command = new GrantPointCommand(USER_ID, new BigDecimal("5000"),
                PointLotOrigin.MANUAL_GRANT, "MANUAL", "cs-1", null, "admin:1", "보상");

        service.grant(command);
        service.grant(command);   // 두 번째는 조용히 단축 반환되어야 한다(예외 금지).

        assertThat(account.getAvailable()).isEqualByComparingTo(new BigDecimal("15000"));
        assertThat(entryPort.count()).isEqualTo(1);
    }
}
