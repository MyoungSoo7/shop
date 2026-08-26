package github.lms.lemuel.operation.site.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface PopupRepository extends JpaRepository<PopupJpaEntity, UUID> {

    /**
     * 지운 팝업은 어떤 목록에도 나오지 않는다 — 조건이 아니라 상수다(강사 명부와 같은 이유).
     *
     * <p>정렬은 {@code sort_order} → {@code created_at} 이다. 순서를 지정하지 않으면 운영자가
     * 정한 노출 순서가 새로고침마다 달라진다. dentis 는 순서 컬럼 자체가 없어 등록 역순으로만
     * 보여 줬고, 그래서 "이 팝업을 맨 앞으로" 가 재등록으로만 가능했다.
     */
    @Query("""
            SELECT p FROM PopupJpaEntity p
            WHERE p.deleted = false
            ORDER BY p.sortOrder ASC, p.createdAt ASC
            """)
    List<PopupJpaEntity> findAllAlive();

    /**
     * 노출 후보 — 켜져 있고 지우지 않은 것들. <b>구간 판정은 하지 않는다</b>. 시각 비교를 쿼리에
     * 넣으면 관리 화면의 "지금 노출 중" 표시와 공개 조회가 서로 다른 두 벌의 규칙을 갖게 되고,
     * dentis 가 시작 시각을 빠뜨린 사고가 정확히 그 형태였다. 판정은 {@code Popup#isVisibleAt} 하나다.
     */
    @Query("""
            SELECT p FROM PopupJpaEntity p
            WHERE p.deleted = false AND p.active = true
            ORDER BY p.sortOrder ASC, p.createdAt ASC
            """)
    List<PopupJpaEntity> findActiveCandidates();
}
