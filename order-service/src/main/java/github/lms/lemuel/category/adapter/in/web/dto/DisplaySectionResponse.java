package github.lms.lemuel.category.adapter.in.web.dto;

import github.lms.lemuel.category.domain.DisplaySection;
import github.lms.lemuel.category.domain.DisplaySectionKind;

import java.time.LocalDateTime;

/** 진열 편성 응답. 노출 여부는 서버가 시각으로 판정한 결과라 별도 필드로 내리지 않는다. */
public record DisplaySectionResponse(Long id,
                                     String code,
                                     String name,
                                     DisplaySectionKind kind,
                                     Long categoryId,
                                     LocalDateTime startsAt,
                                     LocalDateTime endsAt,
                                     int sortOrder,
                                     boolean active) {

    public static DisplaySectionResponse from(DisplaySection section) {
        return new DisplaySectionResponse(section.getId(), section.getCode(), section.getName(),
                section.getKind(), section.getCategoryId(), section.getStartsAt(),
                section.getEndsAt(), section.getSortOrder(), section.isActive());
    }
}
