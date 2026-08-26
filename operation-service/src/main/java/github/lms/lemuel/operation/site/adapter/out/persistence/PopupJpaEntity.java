package github.lms.lemuel.operation.site.adapter.out.persistence;

import github.lms.lemuel.operation.site.domain.Popup;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** 팝업 영속 모델 — 매핑만 한다. 규칙은 도메인 {@link Popup} 가 소유한다. */
@Entity
@Table(name = "site_popups", schema = "site")
public class PopupJpaEntity {

    @Id private UUID id;
    private String title;
    @Column(name = "image_url") private String imageUrl;
    @Column(name = "link_url") private String linkUrl;
    @Column(name = "open_in_new_window") private boolean openInNewWindow;
    @Column(name = "starts_at") private Instant startsAt;
    @Column(name = "ends_at") private Instant endsAt;
    @Column(name = "sort_order") private int sortOrder;
    private boolean active;
    private boolean deleted;
    private Instant deletedAt;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    protected PopupJpaEntity() { }

    static PopupJpaEntity fromDomain(Popup popup) {
        PopupJpaEntity entity = new PopupJpaEntity();
        entity.id = popup.id();
        entity.createdBy = popup.updatedBy();
        entity.createdAt = Instant.now();
        entity.sync(popup);
        return entity;
    }

    /** 도메인 상태를 영속 모델에 반영한다 — 식별자·등록자·등록 시각은 건드리지 않는다. */
    void sync(Popup popup) {
        this.title = popup.title();
        this.imageUrl = popup.imageUrl();
        this.linkUrl = popup.linkUrl();
        this.openInNewWindow = popup.openInNewWindow();
        this.startsAt = popup.startsAt();
        this.endsAt = popup.endsAt();
        this.sortOrder = popup.sortOrder();
        this.active = popup.active();
        this.deleted = popup.deleted();
        this.deletedAt = popup.deletedAt();
        this.updatedBy = popup.updatedBy();
        this.updatedAt = Instant.now();
    }

    Popup toDomain() {
        return Popup.rehydrate(id, title, imageUrl, linkUrl, openInNewWindow, startsAt, endsAt,
                sortOrder, active, deleted, deletedAt, updatedBy, version);
    }

    public UUID getId() { return id; }
}
