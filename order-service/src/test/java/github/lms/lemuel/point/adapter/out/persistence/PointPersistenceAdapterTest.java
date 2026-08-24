package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointAccountStatus;
import github.lms.lemuel.point.domain.PointEarnScope;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotConsumption;
import github.lms.lemuel.point.domain.PointLotOrigin;
import github.lms.lemuel.point.domain.PointLotStatus;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 포인트 영속 어댑터 회귀 테스트 (Mockito, 실 DB 미접속).
 *
 * <p>이 어댑터는 네 포트(계정·로트·원장·적립정책)의 도메인 ↔ JPA 변환을 혼자 짊어진다. 변환이
 * 틀리면 컴파일도 되고 통합테스트도 "저장은 됐다"로 통과하므로, 매핑 왕복과 분기(신규/갱신/유실)를
 * 여기서 못박는다. Testcontainers 없이 도는 층이라 Docker 유무와 무관하게 항상 판정된다.
 */
@ExtendWith(MockitoExtension.class)
class PointPersistenceAdapterTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-18T09:00:00Z");

    @Mock PointAccountRepository accounts;
    @Mock PointLotRepository lots;
    @Mock PointEntryRepository entries;
    @Mock PointEarnPolicyRepository policies;
    @InjectMocks PointPersistenceAdapter adapter;

    private PointAccount persistedAccount(Long id, BigDecimal available) {
        return PointAccount.rehydrate(id, 42L, available, BigDecimal.ZERO, available,
                PointAccountStatus.ACTIVE, 3L, NOW, NOW);
    }

    private PointAccountJpaEntity accountEntity(Long id, BigDecimal available) {
        return PointAccountJpaEntity.from(persistedAccount(id, available));
    }

    private PointLot persistedLot(Long id, BigDecimal remaining) {
        return PointLot.rehydrate(id, 7L, PointLotOrigin.ORDER_EARN, new BigDecimal("100"),
                remaining, PointLotStatus.ACTIVE, NOW, NOW.plusDays(365), "ORDER", "ORD-1", 0L);
    }

    private PointLotJpaEntity lotEntity(Long id, BigDecimal remaining) {
        return PointLotJpaEntity.from(persistedLot(id, remaining));
    }

    // ── PointAccountPort ──────────────────────────────────────────────────────

    @Test
    @DisplayName("계정 조회 3종(일반·사용자락·ID락)이 모두 도메인으로 변환돼 나온다")
    void accountLookupsMapToDomain() {
        when(accounts.findByUserId(42L)).thenReturn(Optional.of(accountEntity(1L, new BigDecimal("500"))));
        when(accounts.lockByUserId(42L)).thenReturn(Optional.of(accountEntity(1L, new BigDecimal("500"))));
        when(accounts.lockById(1L)).thenReturn(Optional.of(accountEntity(1L, new BigDecimal("500"))));

        assertThat(adapter.load(42L)).get().extracting(PointAccount::getAvailable)
                .isEqualTo(new BigDecimal("500.00"));
        assertThat(adapter.loadForUpdate(42L)).get().extracting(PointAccount::getId).isEqualTo(1L);
        assertThat(adapter.loadByIdForUpdate(1L)).get().extracting(PointAccount::getUserId).isEqualTo(42L);
    }

    @Test
    @DisplayName("계정이 없으면 빈 Optional 이 그대로 전달된다")
    void accountLookupMissing() {
        when(accounts.findByUserId(99L)).thenReturn(Optional.empty());

        assertThat(adapter.load(99L)).isEmpty();
    }

    @Test
    @DisplayName("신규 계정 저장은 생성된 ID 와 버전을 도메인에 되꽂는다")
    void saveNewAccountAssignsIdAndVersion() {
        PointAccount opened = PointAccount.open(42L);
        when(accounts.save(any())).thenReturn(accountEntity(11L, BigDecimal.ZERO));

        PointAccount saved = adapter.save(opened);

        assertThat(saved.getId()).isEqualTo(11L);
        verify(accounts, never()).findById(anyLong());
    }

    @Test
    @DisplayName("기존 계정 저장은 조회한 엔티티에 상태를 덮어쓴다 — 새 행을 만들지 않는다")
    void saveExistingAccountAppliesOntoLoadedRow() {
        PointAccount account = persistedAccount(5L, new BigDecimal("300"));
        PointAccountJpaEntity row = accountEntity(5L, new BigDecimal("100"));
        when(accounts.findById(5L)).thenReturn(Optional.of(row));
        when(accounts.save(row)).thenReturn(row);

        adapter.save(account);

        assertThat(row.toDomain().getAvailable()).isEqualTo(new BigDecimal("300.00"));
        verify(accounts).save(row);
    }

    @Test
    @DisplayName("저장하려는 계정 행이 사라졌으면 불변식 위반으로 드러낸다")
    void saveExistingAccountRowVanished() {
        PointAccount account = persistedAccount(5L, new BigDecimal("300"));
        when(accounts.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.save(account))
                .isInstanceOf(PointInvariantViolationException.class)
                .hasMessageContaining("id=5");
    }

    @Test
    @DisplayName("openIfAbsent 는 있으면 그대로, 없으면 새 계정을 연다")
    void openIfAbsent() {
        when(accounts.findByUserId(42L)).thenReturn(Optional.of(accountEntity(1L, new BigDecimal("50"))));

        assertThat(adapter.openIfAbsent(42L).getId()).isEqualTo(1L);
        verify(accounts, never()).save(any());
    }

    @Test
    @DisplayName("openIfAbsent — 계정이 없으면 open 해서 저장까지 간다")
    void openIfAbsentCreates() {
        when(accounts.findByUserId(42L)).thenReturn(Optional.empty());
        when(accounts.save(any())).thenReturn(accountEntity(21L, BigDecimal.ZERO));

        assertThat(adapter.openIfAbsent(42L).getId()).isEqualTo(21L);
    }

    // ── PointLotPort ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("소비 가능 로트 조회는 ACTIVE 상태로만 질의한다")
    void loadConsumableQueriesActiveOnly() {
        when(lots.findConsumable(7L, PointLotStatus.ACTIVE))
                .thenReturn(List.of(lotEntity(1L, new BigDecimal("40"))));

        assertThat(adapter.loadConsumable(7L)).singleElement()
                .extracting(PointLot::getRemainingAmount).isEqualTo(new BigDecimal("40.00"));
    }

    @Test
    @DisplayName("빈 ID 목록은 질의 없이 빈 리스트 — 불필요한 IN () 쿼리를 만들지 않는다")
    void loadByIdsShortCircuitsOnEmpty() {
        assertThat(adapter.loadByIds(List.of())).isEmpty();
        verify(lots, never()).findByIdIn(any());
    }

    @Test
    @DisplayName("ID 목록 조회는 도메인으로 변환해 돌려준다")
    void loadByIds() {
        when(lots.findByIdIn(List.of(1L))).thenReturn(List.of(lotEntity(1L, new BigDecimal("40"))));

        assertThat(adapter.loadByIds(List.of(1L))).singleElement()
                .extracting(PointLot::getId).isEqualTo(1L);
    }

    @Test
    @DisplayName("만료 대상 조회는 ACTIVE + 기준시각 + 건수 제한으로 질의한다")
    void loadExpired() {
        when(lots.findExpired(eq(PointLotStatus.ACTIVE), eq(NOW), any()))
                .thenReturn(List.of(lotEntity(2L, new BigDecimal("10"))));

        assertThat(adapter.loadExpired(NOW, 100)).singleElement()
                .extracting(PointLot::getId).isEqualTo(2L);
    }

    @Test
    @DisplayName("신규 로트 저장은 생성된 ID 를 도메인에 되꽂는다")
    void saveNewLotAssignsId() {
        PointLot issued = PointLot.issue(7L, PointLotOrigin.ORDER_EARN, new BigDecimal("100"),
                NOW, NOW.plusDays(365), "ORDER", "ORD-9");
        when(lots.save(any())).thenReturn(lotEntity(31L, new BigDecimal("100")));

        assertThat(adapter.save(issued).getId()).isEqualTo(31L);
        verify(lots, never()).findById(anyLong());
    }

    @Test
    @DisplayName("기존 로트 저장은 잔량·상태만 기존 행에 반영한다")
    void saveExistingLotAppliesRemaining() {
        PointLot lot = persistedLot(3L, new BigDecimal("25"));
        PointLotJpaEntity row = lotEntity(3L, new BigDecimal("100"));
        when(lots.findById(3L)).thenReturn(Optional.of(row));
        when(lots.save(row)).thenReturn(row);

        adapter.save(lot);

        assertThat(row.toDomain().getRemainingAmount()).isEqualTo(new BigDecimal("25.00"));
    }

    @Test
    @DisplayName("저장하려는 로트 행이 사라졌으면 불변식 위반으로 드러낸다")
    void saveExistingLotRowVanished() {
        PointLot lot = persistedLot(3L, new BigDecimal("25"));
        when(lots.findById(3L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.save(lot))
                .isInstanceOf(PointInvariantViolationException.class)
                .hasMessageContaining("id=3");
    }

    @Test
    @DisplayName("saveAll 은 건별 저장 경로를 그대로 탄다")
    void saveAllDelegatesPerLot() {
        PointLot first = persistedLot(3L, new BigDecimal("25"));
        PointLot second = persistedLot(4L, new BigDecimal("15"));
        when(lots.findById(3L)).thenReturn(Optional.of(lotEntity(3L, new BigDecimal("100"))));
        when(lots.findById(4L)).thenReturn(Optional.of(lotEntity(4L, new BigDecimal("100"))));
        when(lots.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(adapter.saveAll(List.of(first, second))).hasSize(2);
        verify(lots, org.mockito.Mockito.times(2)).save(any());
    }

    // ── PointEntryPort ────────────────────────────────────────────────────────

    @Test
    @DisplayName("원장 엔트리 append 는 저장 후 ID 를 되꽂는다")
    void appendEntry() {
        PointEntry entry = PointEntry.grant(7L, new BigDecimal("100"), "ORDER", "ORD-1", 1,
                List.of(new PointLotConsumption(31L, new BigDecimal("100"))), "system", "적립");
        when(entries.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(adapter.append(entry).getAmount()).isEqualTo(new BigDecimal("100.00"));
        verify(entries).save(any());
    }

    @Test
    @DisplayName("다음 시퀀스는 현재 최대치 + 1")
    void nextSequence() {
        when(entries.maxSequence(7L, PointEntryType.USE, "ORDER", "ORD-1")).thenReturn(2);

        assertThat(adapter.nextSequence(7L, PointEntryType.USE, "ORDER", "ORD-1")).isEqualTo(3);
    }

    @Test
    @DisplayName("멱등 조회 2종(시퀀스 포함·참조만)이 각각의 질의로 내려간다")
    void existenceChecks() {
        when(entries.existsByAccountIdAndEntryTypeAndReferenceTypeAndReferenceIdAndSequence(
                anyLong(), any(), anyString(), anyString(), anyInt())).thenReturn(true);
        when(entries.existsByAccountIdAndEntryTypeAndReferenceTypeAndReferenceId(
                anyLong(), any(), anyString(), anyString())).thenReturn(false);

        assertThat(adapter.exists(7L, PointEntryType.USE, "ORDER", "ORD-1", 1)).isTrue();
        assertThat(adapter.existsByReference(7L, PointEntryType.USE, "ORDER", "ORD-1")).isFalse();
    }

    @Test
    @DisplayName("참조별 엔트리 조회는 시퀀스 오름차순 질의 결과를 도메인으로 변환한다")
    void loadByReference() {
        PointEntry entry = PointEntry.use(7L, new BigDecimal("30"), "ORDER", "ORD-1", 1,
                List.of(new PointLotConsumption(31L, new BigDecimal("30"))), "system");
        when(entries.findByAccountIdAndEntryTypeAndReferenceTypeAndReferenceIdOrderBySequenceAsc(
                7L, PointEntryType.USE, "ORDER", "ORD-1"))
                .thenReturn(List.of(PointEntryJpaEntity.from(entry)));

        assertThat(adapter.loadByReference(7L, PointEntryType.USE, "ORDER", "ORD-1"))
                .singleElement().extracting(PointEntry::getType).isEqualTo(PointEntryType.USE);
    }

    @Test
    @DisplayName("참조로 계정을 찾을 때 0건이면 빈 Optional, 1건이면 그 계정")
    void findAccountIdByReference() {
        when(entries.findAccountIds(PointEntryType.USE, "ORDER", "ORD-1")).thenReturn(List.of());
        assertThat(adapter.findAccountIdByReference(PointEntryType.USE, "ORDER", "ORD-1")).isEmpty();

        when(entries.findAccountIds(PointEntryType.USE, "ORDER", "ORD-2")).thenReturn(List.of(7L));
        assertThat(adapter.findAccountIdByReference(PointEntryType.USE, "ORDER", "ORD-2")).contains(7L);
    }

    @Test
    @DisplayName("같은 참조가 두 계정에 걸쳐 있으면 복원 대상을 정할 수 없어 예외 — 조용히 첫 건을 고르지 않는다")
    void findAccountIdByReferenceAmbiguous() {
        when(entries.findAccountIds(PointEntryType.USE, "ORDER", "ORD-3")).thenReturn(List.of(7L, 8L));

        assertThatThrownBy(() -> adapter.findAccountIdByReference(PointEntryType.USE, "ORDER", "ORD-3"))
                .isInstanceOf(PointInvariantViolationException.class)
                .hasMessageContaining("ORDER:ORD-3");
    }

    // ── PointEarnPolicyPort ───────────────────────────────────────────────────

    @Test
    @DisplayName("적립정책 후보는 GLOBAL 을 항상 담고, 등급·카테고리는 키가 있을 때만 더한다")
    void loadCandidatesAddsScopedPoliciesOnlyWhenKeyPresent() {
        PointEarnPolicyJpaEntity global = org.mockito.Mockito.mock(PointEarnPolicyJpaEntity.class);
        PointEarnPolicyJpaEntity grade = org.mockito.Mockito.mock(PointEarnPolicyJpaEntity.class);
        when(policies.findByScope(eq(PointEarnScope.GLOBAL), any())).thenReturn(List.of(global));
        when(policies.findByScopeAndKey(eq(PointEarnScope.GRADE), eq("VIP"), any()))
                .thenReturn(List.of(grade));

        assertThat(adapter.loadCandidates(NOW.toLocalDate(), "VIP", null)).hasSize(2);
        verify(policies, never()).findByScopeAndKey(eq(PointEarnScope.CATEGORY), anyString(), any());
    }

    @Test
    @DisplayName("등급·카테고리 키가 모두 없으면 GLOBAL 만 조회한다")
    void loadCandidatesGlobalOnly() {
        when(policies.findByScope(eq(PointEarnScope.GLOBAL), any())).thenReturn(List.of());

        assertThat(adapter.loadCandidates(NOW.toLocalDate(), null, null)).isEmpty();
        verify(policies, never()).findByScopeAndKey(any(), anyString(), any());
    }
}
