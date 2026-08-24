package github.lms.lemuel.product.adapter.out.persistence;

import github.lms.lemuel.product.domain.OptionInputType;
import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "option_axes")
public class OptionAxisJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50, unique = true)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "input_type", nullable = false, length = 20)
    private OptionInputType inputType;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected OptionAxisJpaEntity() { }

    public OptionAxisJpaEntity(Long id, String code, String name,
                               OptionInputType inputType, boolean active) {
        this.id = id;
        this.code = code;
        this.name = name;
        this.inputType = inputType;
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

    public void applyDomainState(String name, OptionInputType inputType, boolean active) {
        this.name = name;
        this.inputType = inputType;
        this.active = active;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public OptionInputType getInputType() { return inputType; }
    public boolean isActive() { return active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
