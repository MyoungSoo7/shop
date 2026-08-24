package github.lms.lemuel.bulkorder.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "bulk_order_column_specs")
@Getter
@Setter
@NoArgsConstructor
public class BulkOrderColumnSpecJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "column_index", nullable = false, unique = true)
    private Integer columnIndex;

    @Column(name = "item_code", nullable = false, unique = true, length = 50)
    private String itemCode;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "required", nullable = false)
    private Boolean required;

    @Column(name = "max_length")
    private Integer maxLength;

    @Column(name = "validation_type", nullable = false, length = 20)
    private String validationType;

    @Column(name = "validation_text", length = 500)
    private String validationText;
}
