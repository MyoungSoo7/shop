package github.lms.lemuel.category.application.port.out;

import github.lms.lemuel.category.domain.EcommerceCategory;

import java.util.List;
import java.util.Optional;

/**
 * 카테고리 조회 Outbound Port
 */
public interface LoadEcommerceCategoryPort {

    Optional<EcommerceCategory> findByIdNotDeleted(Long id);

    Optional<EcommerceCategory> findBySlug(String slug);

    List<EcommerceCategory> findAllNotDeleted();

    List<EcommerceCategory> findAllActiveNotDeleted();

    List<EcommerceCategory> findByParentId(Long parentId);

    long countChildrenByParentId(Long parentId);

    boolean hasProducts(Long categoryId);
}
