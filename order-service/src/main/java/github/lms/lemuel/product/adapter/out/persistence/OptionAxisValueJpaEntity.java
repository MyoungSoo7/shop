package github.lms.lemuel.product.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "option_axis_values",
        uniqueConstraints = @UniqueConstraint(name = "uq_option_axis_values_code",
                columnNames = {"axis_id", "code"}))
public class OptionAxisValueJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "axis_id", nullable = false)
    private Long axisId;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "swatch_hex", length = 7)
    private String swatchHex;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected OptionAxisValueJpaEntity() { }

    public OptionAxisValueJpaEntity(Long id, Long axisId, String code, String name,
                                    String swatchHex, int sortOrder, boolean active) {
        this.id = id;
        this.axisId = axisId;
        this.code = code;
        this.name = name;
        this.swatchHex = swatchHex;
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

    public void applyDomainState(String name, String swatchHex, int sortOrder, boolean active) {
        this.name = name;
        this.swatchHex = swatchHex;
        this.sortOrder = sortOrder;
        this.active = active;
    }

    public Long getId() { return id; }
    public Long getAxisId() { return axisId; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getSwatchHex() { return swatchHex; }
    public int getSortOrder() { return sortOrder; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
