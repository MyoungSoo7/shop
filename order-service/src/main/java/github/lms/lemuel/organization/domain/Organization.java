package github.lms.lemuel.organization.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * 조직 애그리게잇 루트 (순수 POJO) — 셀러/기업 단위. 상태 전이는 {@link OrganizationStatus} 상태머신이 강제한다.
 */
public class Organization {

    private final Long id;
    private final String name;
    private final OrganizationType type;
    private final String externalRef;   // sellerId 또는 stockCode (nullable, 비검증 참조)
    private OrganizationStatus status;
    private final Instant createdAt;
    private final long version;

    private Organization(Builder b) {
        this.id = b.id;
        this.name = Objects.requireNonNull(b.name, "name");
        this.type = Objects.requireNonNull(b.type, "type");
        this.externalRef = b.externalRef;
        this.status = Objects.requireNonNull(b.status, "status");
        this.createdAt = b.createdAt;
        this.version = b.version;
    }

    /** 신규 조직 생성 — 기본 ACTIVE. */
    public static Organization create(String name, OrganizationType type, String externalRef) {
        return builder()
                .name(name)
                .type(type)
                .externalRef(externalRef)
                .status(OrganizationStatus.ACTIVE)
                .build();
    }

    public void suspend() {
        transitionTo(OrganizationStatus.SUSPENDED);
    }

    public void activate() {
        transitionTo(OrganizationStatus.ACTIVE);
    }

    private void transitionTo(OrganizationStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidOrganizationTransitionException(status, target);
        }
        this.status = target;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public OrganizationType getType() {
        return type;
    }

    public String getExternalRef() {
        return externalRef;
    }

    public OrganizationStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public long getVersion() {
        return version;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 영속성 어댑터의 재구성과 팩토리가 공용하는 빌더. */
    public static class Builder {
        private Long id;
        private String name;
        private OrganizationType type;
        private String externalRef;
        private OrganizationStatus status;
        private Instant createdAt;
        private long version;

        public Builder id(Long v) {
            this.id = v;
            return this;
        }

        public Builder name(String v) {
            this.name = v;
            return this;
        }

        public Builder type(OrganizationType v) {
            this.type = v;
            return this;
        }

        public Builder externalRef(String v) {
            this.externalRef = v;
            return this;
        }

        public Builder status(OrganizationStatus v) {
            this.status = v;
            return this;
        }

        public Builder createdAt(Instant v) {
            this.createdAt = v;
            return this;
        }

        public Builder version(long v) {
            this.version = v;
            return this;
        }

        public Organization build() {
            return new Organization(this);
        }
    }
}
