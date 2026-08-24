package github.lms.lemuel.menu.adapter.out.persistence;

import github.lms.lemuel.menu.domain.MenuArea;
import github.lms.lemuel.menu.domain.MenuType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "menus", indexes = {
        @Index(name = "idx_menus_parent_id", columnList = "parent_id"),
        @Index(name = "idx_menus_sort_order", columnList = "sort_order"),
        @Index(name = "idx_menus_area", columnList = "area")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MenuJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "short_name", length = 40)
    private String shortName;

    @Column(length = 255)
    private String path;

    @Column(length = 50)
    private String icon;

    @Column(length = 200)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private MenuArea area;

    @Enumerated(EnumType.STRING)
    @Column(name = "menu_type", nullable = false, length = 10)
    private MenuType menuType;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "required_role", length = 60)
    private String requiredRole;

    @Column(name = "required_permission", length = 60)
    private String requiredPermission;

    @Column(nullable = false)
    private boolean visible;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
        if (menuType == null) menuType = MenuType.ITEM;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
