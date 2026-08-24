package github.lms.lemuel.giftcard.adapter.out.persistence;

import github.lms.lemuel.giftcard.application.port.out.GiftCardEntryPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardHoldPort;
import github.lms.lemuel.giftcard.application.port.out.GiftCardPort;
import github.lms.lemuel.giftcard.domain.GiftCard;
import github.lms.lemuel.giftcard.domain.GiftCardEntry;
import github.lms.lemuel.giftcard.domain.GiftCardEntryType;
import github.lms.lemuel.giftcard.domain.GiftCardHold;
import github.lms.lemuel.giftcard.domain.GiftCardHoldStatus;
import github.lms.lemuel.giftcard.domain.GiftCardStatus;
import github.lms.lemuel.giftcard.domain.exception.GiftCardInvariantViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 기프트카드 영속 어댑터 — 도메인 ↔ JPA 변환을 한곳에 모은다.
 *
 * <p>두 아웃바운드 포트를 한 클래스가 구현한다. 카드와 원장은 같은 트랜잭션에서 함께 움직이고
 * 변환 규칙도 공유하므로, 쪼개면 같은 매핑이 두 곳에 흩어진다.
 */
@Component
public class GiftCardPersistenceAdapter implements GiftCardPort, GiftCardEntryPort, GiftCardHoldPort {

    /** 소멸 배치가 훑는 상태 — 아직 잔액이 살아 있을 수 있는 것들. */
    private static final List<GiftCardStatus> EXPIRABLE =
            List.of(GiftCardStatus.ACTIVE, GiftCardStatus.REGISTERED);

    private final GiftCardRepository cards;
    private final GiftCardEntryRepository entries;
    private final GiftCardHoldRepository holds;

    public GiftCardPersistenceAdapter(GiftCardRepository cards, GiftCardEntryRepository entries,
                                      GiftCardHoldRepository holds) {
        this.cards = cards;
        this.entries = entries;
        this.holds = holds;
    }

    // ── GiftCardHoldPort ──────────────────────────────────────────────

    @Override
    public List<GiftCardHold> saveHolds(List<GiftCardHold> toSave) {
        for (GiftCardHold hold : toSave) {
            save(hold);
        }
        return toSave;
    }

    @Override
    public GiftCardHold save(GiftCardHold hold) {
        if (hold.getId() == null) {
            GiftCardHoldJpaEntity saved = holds.save(GiftCardHoldJpaEntity.from(hold));
            hold.assignId(saved.getId());
            return hold;
        }
        // 기존 행은 관리 상태의 엔티티에 변경분만 얹는다 — from() 으로 새로 만들어 save 하면
        // 생성 시각처럼 도메인이 들고 있지 않은 값이 덮여 쓰인다.
        holds.findById(hold.getId())
                .orElseThrow(() -> new GiftCardInvariantViolationException(
                        "저장된 선점을 찾을 수 없습니다: id=" + hold.getId()))
                .apply(hold);
        return hold;
    }

    @Override
    public List<GiftCardHold> findByReference(String referenceType, String referenceId) {
        return holds.findByReferenceTypeAndReferenceId(referenceType, referenceId).stream()
                .map(GiftCardHoldJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<Long> findCardIdsByReference(String referenceType, String referenceId) {
        return holds.findCardIdsByReference(referenceType, referenceId);
    }

    @Override
    public Map<Long, BigDecimal> activeAmountsByCardIds(Collection<Long> cardIds) {
        if (cardIds == null || cardIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        for (Object[] row : holds.sumActiveByCardIds(cardIds, GiftCardHoldStatus.ACTIVE)) {
            result.put((Long) row[0], (BigDecimal) row[1]);
        }
        return result;
    }

    // ── GiftCardPort ──────────────────────────────────────────────────────────

    @Override
    public Optional<GiftCard> loadByCodeHashForUpdate(String codeHash) {
        return cards.lockByCodeHash(codeHash).map(GiftCardJpaEntity::toDomain);
    }

    @Override
    public List<GiftCard> loadSpendable(Long userId) {
        return cards.lockSpendable(userId, GiftCardStatus.REGISTERED).stream()
                .map(GiftCardJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<GiftCard> loadSpendableReadOnly(Long userId) {
        return cards.findSpendable(userId, GiftCardStatus.REGISTERED).stream()
                .map(GiftCardJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<GiftCard> loadForUpdate(Collection<Long> giftCardIds) {
        if (giftCardIds.isEmpty()) {
            return List.of();
        }
        return cards.lockByIds(giftCardIds).stream().map(GiftCardJpaEntity::toDomain).toList();
    }

    @Override
    public List<GiftCard> loadExpired(OffsetDateTime at, int limit) {
        return cards.findExpired(EXPIRABLE, at, PageRequest.of(0, limit)).stream()
                .map(GiftCardJpaEntity::toDomain)
                .toList();
    }

    @Override
    public boolean existsByCodeHash(String codeHash) {
        return cards.existsByCodeHash(codeHash);
    }

    @Override
    public GiftCard save(GiftCard card) {
        GiftCardJpaEntity entity;
        if (card.getId() == null) {
            entity = GiftCardJpaEntity.from(card);
        } else {
            entity = cards.findById(card.getId())
                    .orElseThrow(() -> new GiftCardInvariantViolationException(
                            "저장하려는 카드가 사라졌습니다: id=" + card.getId()));
            entity.apply(card);
        }
        GiftCardJpaEntity saved = cards.save(entity);
        if (card.getId() == null) {
            card.assignId(saved.getId());
        }
        card.syncVersion(saved.getVersion());
        return card;
    }

    @Override
    public List<GiftCard> saveAll(List<GiftCard> toSave) {
        List<GiftCard> result = new ArrayList<>(toSave.size());
        for (GiftCard card : toSave) {
            result.add(save(card));
        }
        return result;
    }

    // ── GiftCardEntryPort ─────────────────────────────────────────────────────

    @Override
    public GiftCardEntry append(GiftCardEntry entry) {
        GiftCardEntryJpaEntity saved = entries.save(GiftCardEntryJpaEntity.from(entry));
        entry.assignId(saved.getId());
        return entry;
    }

    @Override
    public int nextSequence(Long giftCardId, GiftCardEntryType type, String referenceType,
                            String referenceId) {
        return entries.maxSequence(giftCardId, type, referenceType, referenceId) + 1;
    }

    @Override
    public boolean exists(Long giftCardId, GiftCardEntryType type, String referenceType,
                          String referenceId, int sequence) {
        return entries.existsByGiftCardIdAndEntryTypeAndReferenceTypeAndReferenceIdAndSequence(
                giftCardId, type, referenceType, referenceId, sequence);
    }

    @Override
    public boolean existsByReference(Long giftCardId, GiftCardEntryType type,
                                     String referenceType, String referenceId) {
        return entries.existsByGiftCardIdAndEntryTypeAndReferenceTypeAndReferenceId(
                giftCardId, type, referenceType, referenceId);
    }

    @Override
    public List<GiftCardEntry> loadByReference(GiftCardEntryType type, String referenceType,
                                               String referenceId) {
        return entries.findByEntryTypeAndReferenceTypeAndReferenceIdOrderByIdAsc(
                        type, referenceType, referenceId).stream()
                .map(GiftCardEntryJpaEntity::toDomain)
                .toList();
    }
}
