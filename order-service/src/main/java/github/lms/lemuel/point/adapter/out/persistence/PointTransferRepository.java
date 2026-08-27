package github.lms.lemuel.point.adapter.out.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PointTransferRepository extends JpaRepository<PointTransferJpaEntity, Long> {

    Optional<PointTransferJpaEntity> findBySenderUserIdAndRequestId(Long senderUserId, String requestId);

    /** 보낸 것과 받은 것을 한 표에. 최신순 정렬을 DB 에 맡긴다 — 페이지 경계가 여기서 정해지므로. */
    @Query("""
            select t from PointTransferJpaEntity t
            where t.senderUserId = :userId or t.receiverUserId = :userId
            order by t.createdAt desc, t.id desc
            """)
    List<PointTransferJpaEntity> findByParticipant(@Param("userId") Long userId, Pageable pageable);

    /**
     * 선물 번호 채번. 시퀀스는 트랜잭션 밖에서 원자적으로 증가하므로, 롤백된 요청이 쓴 번호는
     * 되돌아오지 않는다 — 번호에 구멍이 생길 수는 있어도 <b>겹치지는 않는다</b>. 겹치지 않는 쪽이
     * 훨씬 중요하다: 같은 번호 두 건은 원장의 {@code referenceId} 를 충돌시킨다.
     */
    @Query(value = "select nextval('point_transfer_no_seq')", nativeQuery = true)
    long nextTransferSequence();
}
