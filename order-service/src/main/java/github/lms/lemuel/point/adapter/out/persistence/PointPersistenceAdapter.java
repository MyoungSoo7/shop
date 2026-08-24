package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.application.port.out.PointAccountPort;
import github.lms.lemuel.point.application.port.out.PointEarnPolicyPort;
import github.lms.lemuel.point.application.port.out.PointEntryPort;
import github.lms.lemuel.point.application.port.out.PointHoldPort;
import github.lms.lemuel.point.application.port.out.PointLotPort;
import github.lms.lemuel.point.domain.PointAccount;
import github.lms.lemuel.point.domain.PointEarnPolicy;
import github.lms.lemuel.point.domain.PointEarnScope;
import github.lms.lemuel.point.domain.PointEntry;
import github.lms.lemuel.point.domain.PointEntryType;
import github.lms.lemuel.point.domain.PointHold;
import github.lms.lemuel.point.domain.PointHoldStatus;
import github.lms.lemuel.point.domain.PointLot;
import github.lms.lemuel.point.domain.PointLotStatus;
import github.lms.lemuel.point.domain.exception.PointInvariantViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 포인트 영속 어댑터 — 도메인 ↔ JPA 변환을 한곳에 모은다.
 *
 * <p>네 개의 아웃바운드 포트를 한 클래스가 구현한다. 같은 트랜잭션 안에서 계정·로트·원장이 함께
 * 움직이고 변환 규칙도 공유하므로, 쪼개면 같은 매핑이 네 곳에 흩어진다.
 */
@Component
public class PointPersistenceAdapter
        implements PointAccountPort, PointLotPort, PointEntryPort, PointEarnPolicyPort, PointHoldPort {

    private final PointAccountRepository accounts;
    private final PointLotRepository lots;
    private final PointEntryRepository entries;
    private final PointEarnPolicyRepository policies;
    private final PointHoldRepository holds;

    public PointPersistenceAdapter(PointAccountRepository accounts, PointLotRepository lots,
                                   PointEntryRepository entries, PointEarnPolicyRepository policies,
                                   PointHoldRepository holds) {
        this.accounts = accounts;
        this.lots = lots;
        this.entries = entries;
        this.policies = policies;
        this.holds = holds;
    }

    // ── PointAccountPort ──────────────────────────────────────────────────────

    @Override
    public Optional<PointAccount> load(Long userId) {
        return accounts.findByUserId(userId).map(PointAccountJpaEntity::toDomain);
    }

    @Override
    public Optional<PointAccount> loadForUpdate(Long userId) {
        return accounts.lockByUserId(userId).map(PointAccountJpaEntity::toDomain);
    }

    @Override
    public Optional<PointAccount> loadByIdForUpdate(Long accountId) {
        return accounts.lockById(accountId).map(PointAccountJpaEntity::toDomain);
    }

    @Override
    public PointAccount save(PointAccount account) {
        PointAccountJpaEntity entity;
        if (account.getId() == null) {
            entity = PointAccountJpaEntity.from(account);
        } else {
            entity = accounts.findById(account.getId())
                    .orElseThrow(() -> new PointInvariantViolationException(
                            "저장하려는 계정이 사라졌습니다: id=" + account.getId()));
            entity.apply(account);
        }
        PointAccountJpaEntity saved = accounts.save(entity);
        if (account.getId() == null) {
            account.assignId(saved.getId());
        }
        account.syncVersion(saved.getVersion());
        return account;
    }

    @Override
    public PointAccount openIfAbsent(Long userId) {
        return accounts.findByUserId(userId)
                .map(PointAccountJpaEntity::toDomain)
                .orElseGet(() -> save(PointAccount.open(userId)));
    }

    // ── PointLotPort ──────────────────────────────────────────────────────────

    @Override
    public List<PointLot> loadConsumable(Long accountId) {
        return lots.findConsumable(accountId, PointLotStatus.ACTIVE).stream()
                .map(PointLotJpaEntity::toDomain)
                .toList();
    }

    @Override
    public List<PointLot> loadByIds(Collection<Long> lotIds) {
        if (lotIds.isEmpty()) {
            return List.of();
        }
        return lots.findByIdIn(lotIds).stream().map(PointLotJpaEntity::toDomain).toList();
    }

    @Override
    public List<PointLot> loadExpired(OffsetDateTime at, int limit) {
        return lots.findExpired(PointLotStatus.ACTIVE, at, PageRequest.of(0, limit)).stream()
                .map(PointLotJpaEntity::toDomain)
                .toList();
    }

    @Override
    public PointLot save(PointLot lot) {
        PointLotJpaEntity entity;
        if (lot.getId() == null) {
            entity = PointLotJpaEntity.from(lot);
        } else {
            entity = lots.findById(lot.getId())
                    .orElseThrow(() -> new PointInvariantViolationException(
                            "저장하려는 로트가 사라졌습니다: id=" + lot.getId()));
            entity.apply(lot);
        }
        PointLotJpaEntity saved = lots.save(entity);
        if (lot.getId() == null) {
            lot.assignId(saved.getId());
        }
        return lot;
    }

    @Override
    public List<PointLot> saveAll(List<PointLot> toSave) {
        List<PointLot> result = new ArrayList<>(toSave.size());
        for (PointLot lot : toSave) {
            result.add(save(lot));
        }
        return result;
    }

    // ── PointEntryPort ────────────────────────────────────────────────────────

    @Override
    public PointEntry append(PointEntry entry) {
        PointEntryJpaEntity saved = entries.save(PointEntryJpaEntity.from(entry));
        entry.assignId(saved.getId());
        return entry;
    }

    @Override
    public int nextSequence(Long accountId, PointEntryType type, String referenceType, String referenceId) {
        return entries.maxSequence(accountId, type, referenceType, referenceId) + 1;
    }

    @Override
    public boolean exists(Long accountId, PointEntryType type, String referenceType,
                          String referenceId, int sequence) {
        return entries.existsByAccountIdAndEntryTypeAndReferenceTypeAndReferenceIdAndSequence(
                accountId, type, referenceType, referenceId, sequence);
    }

    @Override
    public boolean existsByReference(Long accountId, PointEntryType type,
                                     String referenceType, String referenceId) {
        return entries.existsByAccountIdAndEntryTypeAndReferenceTypeAndReferenceId(
                accountId, type, referenceType, referenceId);
    }

    @Override
    public List<PointEntry> loadByReference(Long accountId, PointEntryType type,
                                            String referenceType, String referenceId) {
        return entries
                .findByAccountIdAndEntryTypeAndReferenceTypeAndReferenceIdOrderBySequenceAsc(
                        accountId, type, referenceType, referenceId)
                .stream()
                .map(PointEntryJpaEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<Long> findAccountIdByReference(PointEntryType type, String referenceType,
                                                   String referenceId) {
        List<Long> accountIds = entries.findAccountIds(type, referenceType, referenceId);
        if (accountIds.size() > 1) {
            // 같은 참조가 두 계정에 걸쳐 있으면 어느 쪽으로 복원할지 결정할 수 없다 — 데이터 손상 신호.
            throw new PointInvariantViolationException(
                    "같은 참조가 여러 계정에 존재합니다: " + referenceType + ":" + referenceId);
        }
        return accountIds.stream().findFirst();
    }

    // ── PointHoldPort ─────────────────────────────────────────────────────────

    @Override
    public PointHold save(PointHold hold) {
        if (hold.getId() == null) {
            PointHoldJpaEntity saved = holds.save(PointHoldJpaEntity.from(hold));
            hold.assignId(saved.getId());
            return hold;
        }
        // 기존 행은 관리 상태의 엔티티에 변경분만 얹는다 — from() 으로 새 인스턴스를 만들어 save 하면
        // 생성 시각처럼 도메인이 들고 있지 않은 값이 덮여 쓰인다.
        holds.findById(hold.getId())
                .orElseThrow(() -> new PointInvariantViolationException(
                        "저장된 선점을 찾을 수 없습니다: id=" + hold.getId()))
                .apply(hold);
        return hold;
    }

    @Override
    public Optional<PointHold> findByReference(String referenceType, String referenceId) {
        return holds.findByReferenceTypeAndReferenceId(referenceType, referenceId)
                .map(PointHoldJpaEntity::toDomain);
    }

    @Override
    public Optional<Long> findAccountIdByReference(String referenceType, String referenceId) {
        return holds.findAccountIdByReference(referenceType, referenceId);
    }

    @Override
    public BigDecimal activeAmount(Long accountId) {
        return holds.sumActive(accountId, PointHoldStatus.ACTIVE);
    }

    // ── PointEarnPolicyPort ───────────────────────────────────────────────────

    @Override
    public List<PointEarnPolicy> loadCandidates(LocalDate on, String gradeKey, String categoryKey) {
        List<PointEarnPolicy> candidates = new ArrayList<>(
                policies.findByScope(PointEarnScope.GLOBAL, on).stream()
                        .map(PointEarnPolicyJpaEntity::toDomain)
                        .toList());
        if (gradeKey != null) {
            policies.findByScopeAndKey(PointEarnScope.GRADE, gradeKey, on)
                    .forEach(entity -> candidates.add(entity.toDomain()));
        }
        if (categoryKey != null) {
            policies.findByScopeAndKey(PointEarnScope.CATEGORY, categoryKey, on)
                    .forEach(entity -> candidates.add(entity.toDomain()));
        }
        return candidates;
    }
}
