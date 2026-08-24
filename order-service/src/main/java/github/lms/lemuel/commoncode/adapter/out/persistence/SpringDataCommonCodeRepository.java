package github.lms.lemuel.commoncode.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpringDataCommonCodeRepository extends JpaRepository<CommonCodeJpaEntity, Long> {

    List<CommonCodeJpaEntity> findByGroupCodeOrderBySortOrderAsc(String groupCode);

    void deleteByGroupCode(String groupCode);
}
