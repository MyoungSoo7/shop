package github.lms.lemuel.menu.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataMenuJpaRepository extends JpaRepository<MenuJpaEntity, Long> {

    /** sort_order 오름차순으로 전체 조회 */
    List<MenuJpaEntity> findAllByOrderBySortOrderAsc();

    /** 특정 부모의 자식 존재 여부 확인 */
    boolean existsByParentId(Long parentId);
}
