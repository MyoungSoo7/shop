package github.lms.lemuel.organization.adapter.out.persistence;

import github.lms.lemuel.organization.domain.Organization;
import github.lms.lemuel.organization.domain.OrganizationStatus;
import github.lms.lemuel.organization.domain.OrganizationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * organizations 테이블 매핑 (V1). created_at/updated_at 은 DB DEFAULT NOW() 에 위임(insertable=false) —
 * 어댑터가 도메인 스냅샷으로 detached 엔티티를 재구성해 merge 하므로 감사 컬럼을 덮어쓰지 않게 한다.
 */
@Entity
@Table(name = "organizations")
public class OrganizationJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrganizationType type;

    @Column(name = "external_ref", length = 64)
    private String externalRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrganizationStatus status;

    @Column(name = "created_at", insertable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", insertable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected OrganizationJpaEntity() {
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public static OrganizationJpaEntity fromDomain(Organization o) {
        OrganizationJpaEntity e = new OrganizationJpaEntity();
        e.id = o.getId();
        e.name = o.getName();
        e.type = o.getType();
        e.externalRef = o.getExternalRef();
        e.status = o.getStatus();
        e.version = o.getVersion();
        return e;
    }

    public Organization toDomain() {
        return Organization.builder()
                .id(id)
                .name(name)
                .type(type)
                .externalRef(externalRef)
                .status(status)
                .createdAt(createdAt)
                .version(version)
                .build();
    }

    public Long getId() {
        return id;
    }
}
