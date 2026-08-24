package github.lms.lemuel.giftcard.adapter.out.persistence;

import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardEntryType;
import github.lms.lemuel.giftcard.domain.GiftCardStatus;
import github.lms.lemuel.giftcard.domain.exception.GiftCardInvariantViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Collection;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기프트카드 영속 어댑터 회귀 테스트 (Mockito, 실 DB 미접속).
 *
 * <p>포인트 영속 어댑터와 같은 이유로 둔다 — 도메인 ↔ JPA 변환이 이 한 곳에 모여 있고, 변환이
 * 틀려도 컴파일과 "저장은 됐다" 류 통합테스트는 통과한다. 소멸 배치가 훑는 상태 집합(EXPIRABLE)
 * 처럼 **여기서만 결정되는 규칙**도 있어, 어댑터를 안 덮으면 그 규칙에 증인이 없다.
 * Testcontainers 를 쓰지 않아 Docker 유무와 무관하게 항상 판정된다.
 */
@ExtendWith(MockitoExtension.class)
class GiftCardPersistenceAdapterTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-19T00:00:00Z");

    @Mock GiftCardRepository cards;
    @Mock GiftCardEntryRepository entries;
    @InjectMocks GiftCardPersistenceAdapter adapter;

    private GiftCard registeredCard(Long id, BigDecimal remaining) {
        return GiftCard.rehydrate(id, "hash-" + id, "1234", new BigDecimal("50000"), remaining,
                GiftCardStatus.REGISTERED, 42L, NOW.minusDays(10), NOW.minusDays(9), NOW.minusDays(8),
                NOW.plusDays(355), "admin", "메모", 2L);
    }

    private GiftCardJpaEntity cardEntity(Long id, BigDecimal remaining) {
        return GiftCardJpaEntity.from(registeredCard(id, remaining));
    }

    // ── GiftCardPort ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("코드해시 잠금 조회는 도메인으로 변환해 돌려준다")
    void loadByCodeHashForUpdate() {
        when(cards.lockByCodeHash("hash-1")).thenReturn(Optional.of(cardEntity(1L, new BigDecimal("30000"))));

        assertThat(adapter.loadByCodeHashForUpdate("hash-1")).get()
                .extracting(GiftCard::getRemainingAmount).isEqualTo(new BigDecimal("30000.00"));
    }

    @Test
    @DisplayName("코드해시로 못 찾으면 빈 Optional 이 그대로 전달된다")
    void loadByCodeHashMissing() {
        when(cards.lockByCodeHash("없음")).thenReturn(Optional.empty());

        assertThat(adapter.loadByCodeHashForUpdate("없음")).isEmpty();
    }

    @Test
    @DisplayName("사용 가능 카드 조회는 잠금·비잠금 모두 REGISTERED 로만 질의한다")
    void loadSpendableQueriesRegisteredOnly() {
        when(cards.lockSpendable(42L, GiftCardStatus.REGISTERED))
                .thenReturn(List.of(cardEntity(1L, new BigDecimal("30000"))));
        when(cards.findSpendable(42L, GiftCardStatus.REGISTERED))
                .thenReturn(List.of(cardEntity(2L, new BigDecimal("10000"))));

        assertThat(adapter.loadSpendable(42L)).singleElement()
                .extracting(GiftCard::getId).isEqualTo(1L);
        assertThat(adapter.loadSpendableReadOnly(42L)).singleElement()
                .extracting(GiftCard::getId).isEqualTo(2L);
    }

    @Test
    @DisplayName("빈 ID 목록은 질의 없이 빈 리스트 — 불필요한 IN () 잠금을 걸지 않는다")
    void loadForUpdateShortCircuitsOnEmpty() {
        assertThat(adapter.loadForUpdate(List.of())).isEmpty();
        verify(cards, never()).lockByIds(any());
    }

    @Test
    @DisplayName("ID 목록 잠금 조회는 도메인으로 변환해 돌려준다")
    void loadForUpdate() {
        when(cards.lockByIds(List.of(1L))).thenReturn(List.of(cardEntity(1L, new BigDecimal("30000"))));

        assertThat(adapter.loadForUpdate(List.of(1L))).singleElement()
                .extracting(GiftCard::getId).isEqualTo(1L);
    }

    @Test
    @DisplayName("소멸 대상은 ACTIVE·REGISTERED 두 상태만 훑는다 — 이 집합은 어댑터가 정한다")
    @SuppressWarnings("unchecked") // ArgumentCaptor 는 제네릭 컬렉션 캡처를 타입 안전하게 표현하지 못한다
    void loadExpiredScansOnlyExpirableStatuses() {
        ArgumentCaptor<Collection<GiftCardStatus>> statuses = ArgumentCaptor.forClass(Collection.class);
        when(cards.findExpired(any(), eq(NOW), any()))
                .thenReturn(List.of(cardEntity(3L, new BigDecimal("500"))));

        assertThat(adapter.loadExpired(NOW, 50)).singleElement()
                .extracting(GiftCard::getId).isEqualTo(3L);

        verify(cards).findExpired(statuses.capture(), eq(NOW), any());
        assertThat(statuses.getValue())
                .containsExactly(GiftCardStatus.ACTIVE, GiftCardStatus.REGISTERED);
    }

    @Test
    @DisplayName("코드해시 중복 여부는 저장소 질의를 그대로 전달한다")
    void existsByCodeHash() {
        when(cards.existsByCodeHash("hash-1")).thenReturn(true);

        assertThat(adapter.existsByCodeHash("hash-1")).isTrue();
    }

    @Test
    @DisplayName("신규 카드 저장은 생성된 ID 를 도메인에 되꽂는다")
    void saveNewCardAssignsId() {
        GiftCard issued = GiftCard.issue("hash-new", "9999", new BigDecimal("50000"),
                NOW, NOW.plusDays(365), "admin", "신규");
        when(cards.save(any())).thenReturn(cardEntity(9L, new BigDecimal("50000")));

        assertThat(adapter.save(issued).getId()).isEqualTo(9L);
        verify(cards, never()).findById(anyLong());
    }

    @Test
    @DisplayName("기존 카드 저장은 조회한 행에 잔액·상태를 덮어쓴다 — 새 행을 만들지 않는다")
    void saveExistingCardAppliesOntoLoadedRow() {
        GiftCard card = registeredCard(5L, new BigDecimal("12000"));
        GiftCardJpaEntity row = cardEntity(5L, new BigDecimal("50000"));
        when(cards.findById(5L)).thenReturn(Optional.of(row));
        when(cards.save(row)).thenReturn(row);

        adapter.save(card);

        assertThat(row.toDomain().getRemainingAmount()).isEqualTo(new BigDecimal("12000.00"));
        verify(cards).save(row);
    }

    @Test
    @DisplayName("저장하려는 카드 행이 사라졌으면 불변식 위반으로 드러낸다")
    void saveExistingCardRowVanished() {
        GiftCard card = registeredCard(5L, new BigDecimal("12000"));
        when(cards.findById(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.save(card))
                .isInstanceOf(GiftCardInvariantViolationException.class)
                .hasMessageContaining("id=5");
    }

    @Test
    @DisplayName("saveAll 은 건별 저장 경로를 그대로 탄다")
    void saveAllDelegatesPerCard() {
        when(cards.findById(anyLong())).thenAnswer(call ->
                Optional.of(cardEntity(call.getArgument(0), new BigDecimal("50000"))));
        when(cards.save(any())).thenAnswer(call -> call.getArgument(0));

        List<GiftCard> saved = adapter.saveAll(
                List.of(registeredCard(1L, new BigDecimal("100")), registeredCard(2L, new BigDecimal("200"))));

        assertThat(saved).hasSize(2);
        verify(cards, times(2)).save(any());
    }

    // ── GiftCardEntryPort ─────────────────────────────────────────────────────

    @Test
    @DisplayName("원장 엔트리 append 는 저장 결과를 도메인에 반영한다")
    void appendEntry() {
        GiftCardEntry entry = GiftCardEntry.use(1L, new BigDecimal("5000"), "ORDER", "ORD-1", 1, "system");
        when(entries.save(any())).thenAnswer(call -> call.getArgument(0));

        assertThat(adapter.append(entry).getAmount()).isEqualTo(new BigDecimal("5000.00"));
        verify(entries).save(any());
    }

    @Test
    @DisplayName("다음 시퀀스는 현재 최대치 + 1")
    void nextSequence() {
        when(entries.maxSequence(1L, GiftCardEntryType.USE, "ORDER", "ORD-1")).thenReturn(4);

        assertThat(adapter.nextSequence(1L, GiftCardEntryType.USE, "ORDER", "ORD-1")).isEqualTo(5);
    }

    @Test
    @DisplayName("멱등 조회 2종(시퀀스 포함·참조만)이 각각의 질의로 내려간다")
    void existenceChecks() {
        when(entries.existsByGiftCardIdAndEntryTypeAndReferenceTypeAndReferenceIdAndSequence(
                anyLong(), any(), anyString(), anyString(), anyInt())).thenReturn(false);
        when(entries.existsByGiftCardIdAndEntryTypeAndReferenceTypeAndReferenceId(
                anyLong(), any(), anyString(), anyString())).thenReturn(true);

        assertThat(adapter.exists(1L, GiftCardEntryType.USE, "ORDER", "ORD-1", 1)).isFalse();
        assertThat(adapter.existsByReference(1L, GiftCardEntryType.USE, "ORDER", "ORD-1")).isTrue();
    }

    @Test
    @DisplayName("참조별 엔트리 조회는 카드 구분 없이 ID 오름차순으로 모아 도메인으로 변환한다")
    void loadByReference() {
        GiftCardEntry first = GiftCardEntry.use(1L, new BigDecimal("5000"), "ORDER", "ORD-1", 1, "system");
        GiftCardEntry second = GiftCardEntry.use(2L, new BigDecimal("3000"), "ORDER", "ORD-1", 1, "system");
        when(entries.findByEntryTypeAndReferenceTypeAndReferenceIdOrderByIdAsc(
                GiftCardEntryType.USE, "ORDER", "ORD-1"))
                .thenReturn(List.of(GiftCardEntryJpaEntity.from(first), GiftCardEntryJpaEntity.from(second)));

        assertThat(adapter.loadByReference(GiftCardEntryType.USE, "ORDER", "ORD-1"))
                .extracting(GiftCardEntry::getGiftCardId).containsExactly(1L, 2L);
    }
}
