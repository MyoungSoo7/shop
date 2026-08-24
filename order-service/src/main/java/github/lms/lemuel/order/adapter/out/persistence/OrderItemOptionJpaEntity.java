package github.lms.lemuel.order.adapter.out.persistence;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "order_item_options",
        uniqueConstraints = @UniqueConstraint(name = "uq_order_item_options",
                columnNames = {"order_item_id", "axis_sort_order"}))
public class OrderItemOptionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_item_id", nullable = false)
    private Long orderItemId;

    @Column(name = "axis_sort_order", nullable = false)
    private int axisSortOrder;

    @Column(name = "axis_code", nullable = false, length = 50)
    private String axisCode;

    @Column(name = "axis_name", nullable = false, length = 100)
    private String axisName;

    @Column(name = "value_code", nullable = false, length = 50)
    private String valueCode;

    @Column(name = "value_name", nullable = false, length = 100)
    private String valueName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    protected OrderItemOptionJpaEntity() { }

    public OrderItemOptionJpaEntity(Long id, Long orderItemId, int axisSortOrder, String axisCode,
                                    String axisName, String valueCode, String valueName) {
        this.id = id;
        this.orderItemId = orderItemId;
        this.axisSortOrder = axisSortOrder;
        this.axisCode = axisCode;
        this.axisName = axisName;
        this.valueCode = valueCode;
        this.valueName = valueName;
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getOrderItemId() { return orderItemId; }
    public int getAxisSortOrder() { return axisSortOrder; }
    public String getAxisCode() { return axisCode; }
    public String getAxisName() { return axisName; }
    public String getValueCode() { return valueCode; }
    public String getValueName() { return valueName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
