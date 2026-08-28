package github.lms.lemuel.marketing.application.port.out;

import github.lms.lemuel.marketing.domain.RewardGrant;
import github.lms.lemuel.marketing.domain.RewardSource;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** 보상 요청 적재·저장. */
public interface RewardGrantPort {

    Optional<RewardGrant> findById(UUID rewardId);

    /** 원본 한 건에 보상 한 건 — 재시도가 두 번 요청하는 걸 막는 조회다. */
    Optional<RewardGrant> findByReference(RewardSource source, UUID referenceId);

    /** 지급일이 지난 대기분. 정산 스케줄러가 부른다. */
    List<RewardGrant> findDue(LocalDate on, int limit);

    List<RewardGrant> findByMember(String memberRef, int limit);

    RewardGrant save(RewardGrant grant);

    /**
     * 요청만 나가고 확정이 돌아오지 않은 보상 건수 — 감시용.
     *
     * <p>왕복의 <b>돌아오는</b> 다리가 끊긴 상태를 센다. outbox 적체로는 안 잡힌다: 발행은
     * 성공했기 때문이다. order 가 죽었거나, 적립 컨슈머가 예외로 계속 재시도 중이거나,
     * 토픽·그룹이 어긋났을 때 여기만 늘어난다. 사용자 화면에는 "적립 처리 중" 으로 남고,
     * 아무도 안 보면 문의가 들어올 때까지 조용하다.
     *
     * @param before 이 시각 이전에 요청된 건만 — 방금 나간 정상 왕복까지 세면 항상 0 이 아니다
     */
    long countRequestedBefore(Instant before);
}
