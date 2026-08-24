package github.lms.lemuel.bulkorder.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "bulk_order_cells")
@Getter
@Setter
@NoArgsConstructor
public class BulkOrderCellJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "row_id", nullable = false)
    private BulkOrderRowJpaEntity row;

    @Column(name = "column_index", nullable = false)
    private Integer columnIndex;

    @Column(name = "cell_value", length = 500)
    private String cellValue;

    @Column(name = "valid", nullable = false)
    private Boolean valid;

    @Column(name = "error_message", length = 500)
    private String errorMessage;
}
