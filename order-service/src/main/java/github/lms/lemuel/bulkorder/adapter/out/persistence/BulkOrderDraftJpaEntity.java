package github.lms.lemuel.bulkorder.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 대량주문 초안.
 *
 * <p>행·셀을 {@code CascadeType.ALL + orphanRemoval} 로 함께 저장한다. 초안은 파일 하나가
 * 통째로 한 덩어리이고 행만 따로 살아남을 이유가 없다 — 애그리거트 경계가 곧 저장 단위다.
 */
@Entity
@Table(name = "bulk_order_drafts")
@Getter
@Setter
@NoArgsConstructor
public class BulkOrderDraftJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uploader_user_id", nullable = false)
    private Long uploaderUserId;

    @Column(name = "file_name", length = 255)
    private String fileName;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "draft", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("lineNumber ASC")
    private List<BulkOrderRowJpaEntity> rows = new ArrayList<>();

    public void replaceRows(List<BulkOrderRowJpaEntity> newRows) {
        this.rows.clear();
        for (BulkOrderRowJpaEntity row : newRows) {
            row.setDraft(this);
            this.rows.add(row);
        }
    }

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) uploadedAt = LocalDateTime.now();
        if (updatedAt == null) updatedAt = LocalDateTime.now();
    }
}
