package github.lms.lemuel.category.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class CategoryNotFoundException extends BusinessException {
    public CategoryNotFoundException(Long id) {
        super(ErrorCode.CATEGORY_NOT_FOUND, "Category not found: " + id);
    }

    public CategoryNotFoundException(String slug) {
        super(ErrorCode.CATEGORY_NOT_FOUND, "Category not found with slug: " + slug);
    }
}
