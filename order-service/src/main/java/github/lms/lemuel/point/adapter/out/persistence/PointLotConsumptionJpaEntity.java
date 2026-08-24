package github.lms.lemuel.point.adapter.out.persistence;

import github.lms.lemuel.point.domain.PointLotConsumption;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/** {@code point_lot_consumptions} 매핑 — 엔트리별 로트 배분 상세. */
@Entity
@Table(name = "point_lot_consumptions")
public class PointLotConsumptionJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;

    protected PointLotConsumptionJpaEntity() {
    }

    static PointLotConsumptionJpaEntity from(PointLotConsumption consumption) {
        PointLotConsumptionJpaEntity entity = new PointLotConsumptionJpaEntity();
        entity.lotId = consumption.lotId();
        entity.amount = consumption.amount();
        return entity;
    }

    PointLotConsumption toDomain() {
        return new PointLotConsumption(lotId, amount);
    }
}
