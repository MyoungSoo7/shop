package github.lms.lemuel.operation.site.adapter.out.persistence;

import github.lms.lemuel.operation.site.application.port.out.LoadPopupPort;
import github.lms.lemuel.operation.site.application.port.out.SavePopupPort;
import github.lms.lemuel.operation.site.domain.Popup;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 팝업 영속 어댑터 — Spring Data 타입은 이 경계 안에서만 쓴다. */
@Component
public class PopupPersistenceAdapter implements LoadPopupPort, SavePopupPort {

    private final PopupRepository popups;

    public PopupPersistenceAdapter(PopupRepository popups) { this.popups = popups; }

    @Override
    public Optional<Popup> findById(UUID id) {
        return popups.findById(id).map(PopupJpaEntity::toDomain);
    }

    @Override
    public List<Popup> findAll() {
        return popups.findAllAlive().stream().map(PopupJpaEntity::toDomain).toList();
    }

    @Override
    public List<Popup> findVisibleAt(Instant now) {
        // 후보만 DB 에서 좁히고 구간 판정은 도메인에 맡긴다. 팝업은 사이트 전체에 몇 개뿐이라
        // 후보를 다 읽어도 비용이 없고, 규칙이 두 군데로 갈라지는 비용이 훨씬 크다.
        return popups.findActiveCandidates().stream()
                .map(PopupJpaEntity::toDomain)
                .filter(popup -> popup.isVisibleAt(now))
                .toList();
    }

    @Override
    public Popup save(Popup popup) {
        PopupJpaEntity entity = popups.findById(popup.id()).orElse(null);
        if (entity == null) {
            entity = PopupJpaEntity.fromDomain(popup);
        } else {
            entity.sync(popup);
        }
        return popups.save(entity).toDomain();
    }
}
