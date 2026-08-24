package github.lms.lemuel.point.application.port.out;

import github.lms.lemuel.point.domain.PointLot;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 포인트 로트 적재·저장 포트.
 *
 * <p>{@link #loadConsumable} 은 소비 가능한(ACTIVE, 잔량 &gt; 0) 로트만 돌려준다. 정렬은
 * {@code PointLotSelector} 가 책임지므로 순서를 약속하지 않는다 — 저장소가 정렬을 보장한다고
 * 믿으면, 인덱스가 바뀌는 날 소비 순서가 조용히 달라진다.
 */
public interface PointLotPort {

    List<PointLot> loadConsumable(Long accountId);

    /** 환불 복원 때 원 로트를 찾기 위해 id 로 적재한다. */
    List<PointLot> loadByIds(Collection<Long> lotIds);

    /** 소멸 배치용 — {@code expiresAt < at} 인 ACTIVE 로트를 최대 {@code limit} 건. */
    List<PointLot> loadExpired(OffsetDateTime at, int limit);

    PointLot save(PointLot lot);

    List<PointLot> saveAll(List<PointLot> lots);
}
