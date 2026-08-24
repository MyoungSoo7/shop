package github.lms.lemuel.category.adapter.in.web.dto;

import github.lms.lemuel.category.domain.DisplaySectionItem;

/** 편성에 담긴 상품. 정렬은 서버가 이미 고정 우선으로 맞춰 내려준다. */
public record DisplaySectionItemResponse(Long productId, int sortOrder, boolean pinned) {

    public static DisplaySectionItemResponse from(DisplaySectionItem item) {
        return new DisplaySectionItemResponse(item.getProductId(), item.getSortOrder(), item.isPinned());
    }
}
