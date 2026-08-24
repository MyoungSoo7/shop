package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.SafetyNumber;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** 안심번호 풀 조회·저장. */
public interface SafetyNumberPort {

    /** 이 주문에 이미 배정된 번호(멱등 재발급의 근거). */
    Optional<SafetyNumber> findAssignedByOrderId(Long orderId);

    /** 풀에서 대기 중인 번호 하나를 행 잠금으로 집는다. 풀이 말랐으면 비어 있음. */
    Optional<SafetyNumber> claimAvailable();

    /** 만료된 배정 목록(회수 대상). */
    List<SafetyNumber> findExpired(OffsetDateTime now, int limit);

    SafetyNumber save(SafetyNumber safetyNumber);
}
