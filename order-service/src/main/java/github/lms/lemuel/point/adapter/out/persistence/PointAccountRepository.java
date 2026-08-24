package github.lms.lemuel.point.adapter.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PointAccountRepository extends JpaRepository<PointAccountJpaEntity, Long> {

    Optional<PointAccountJpaEntity> findByUserId(Long userId);

    /**
     * 잔고를 바꾸는 경로 전용 비관적 락. 잔액 확인과 차감 사이에 다른 요청이 끼어들면
     * 같은 포인트가 두 번 쓰인다 — 재고 read-modify-write 와 같은 함정이다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PointAccountJpaEntity a where a.userId = :userId")
    Optional<PointAccountJpaEntity> lockByUserId(@Param("userId") Long userId);

    /** 소멸 배치는 로트에서 출발하므로 userId 를 모른 채 계정을 잠가야 한다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PointAccountJpaEntity a where a.id = :id")
    Optional<PointAccountJpaEntity> lockById(@Param("id") Long id);
}
