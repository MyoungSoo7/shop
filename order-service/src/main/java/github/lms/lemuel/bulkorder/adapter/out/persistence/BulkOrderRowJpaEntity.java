package github.lms.lemuel.bulkorder.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "bulk_order_rows")
@Getter
@Setter
@NoArgsConstructor
public class BulkOrderRowJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "draft_id", nullable = false)
    private BulkOrderDraftJpaEntity draft;

    @Column(name = "line_number", nullable = false)
    private Integer lineNumber;

    @Column(name = "valid", nullable = false)
    private Boolean valid;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 확정으로 생성된 주문. 재확정에서 이 행을 건너뛰는 근거(중복 주문 방지). */
    @Column(name = "created_order_id")
    private Long createdOrderId;

    @OneToMany(mappedBy = "row", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("columnIndex ASC")
    private List<BulkOrderCellJpaEntity> cells = new ArrayList<>();

    public void replaceCells(List<BulkOrderCellJpaEntity> newCells) {
        this.cells.clear();
        for (BulkOrderCellJpaEntity cell : newCells) {
            cell.setRow(this);
            this.cells.add(cell);
        }
    }
}
