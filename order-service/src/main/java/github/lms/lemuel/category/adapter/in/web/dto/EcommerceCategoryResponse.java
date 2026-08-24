package github.lms.lemuel.category.adapter.in.web.dto;

import github.lms.lemuel.category.domain.EcommerceCategory;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EcommerceCategoryResponse {
    private Long id;
    private String name;
    private String slug;
    private Long parentId;
    private Integer depth;
    private Integer sortOrder;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private List<EcommerceCategoryResponse> children;

    public static EcommerceCategoryResponse from(EcommerceCategory category) {
        return new EcommerceCategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getParentId(),
                category.getDepth(),
                category.getSortOrder(),
                category.getIsActive(),
                category.getCreatedAt(),
                category.getUpdatedAt(),
                category.getChildren().stream()
                        .map(EcommerceCategoryResponse::from)
                        .collect(Collectors.toList())
        );
    }

    public static EcommerceCategoryResponse fromWithoutChildren(EcommerceCategory category) {
        return new EcommerceCategoryResponse(
                category.getId(),
                category.getName(),
                category.getSlug(),
                category.getParentId(),
                category.getDepth(),
                category.getSortOrder(),
                category.getIsActive(),
                category.getCreatedAt(),
                category.getUpdatedAt(),
                new ArrayList<>()
        );
    }
}
