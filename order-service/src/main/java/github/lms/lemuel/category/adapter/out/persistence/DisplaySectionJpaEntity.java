package github.lms.lemuel.category.adapter.out.persistence;

import github.lms.lemuel.category.domain.DisplaySectionKind;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "display_sections")
public class DisplaySectionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DisplaySectionKind kind;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "starts_at")
    private LocalDateTime startsAt;

    @Column(name = "ends_at")
    private LocalDateTime endsAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected DisplaySectionJpaEntity() { }

    public DisplaySectionJpaEntity(Long id, String code, String name, DisplaySectionKind kind,
                                   Long categoryId, LocalDateTime startsAt, LocalDateTime endsAt,
                                   int sortOrder, boolean active) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.kind = kind;
        this.categoryId = categoryId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public void applyDomainState(String name, Long categoryId, LocalDateTime startsAt,
                                 LocalDateTime endsAt, int sortOrder, boolean active) {
        this.name = name;
        this.categoryId = categoryId;
        this.startsAt = startsAt;
        this.endsAt = endsAt;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public DisplaySectionKind getKind() { return kind; }
    public Long getCategoryId() { return categoryId; }
    public LocalDateTime getStartsAt() { return startsAt; }
    public LocalDateTime getEndsAt() { return endsAt; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
}
