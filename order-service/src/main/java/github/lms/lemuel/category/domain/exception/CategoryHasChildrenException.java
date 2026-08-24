package github.lms.lemuel.category.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

public class CategoryHasChildrenException extends BusinessException {
    public CategoryHasChildrenException(Long categoryId, long childCount) {
        super(ErrorCode.CATEGORY_HAS_CHILDREN,
                String.format("Cannot delete category %d: has %d child categories", categoryId, childCount));
    }
}
