package github.lms.lemuel.menu.adapter.in.web.dto;

import github.lms.lemuel.menu.domain.Menu;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Schema(description = "메뉴 응답 (트리 또는 평면)")
public record MenuResponse(
        @Schema(description = "메뉴 ID") Long id,
        @Schema(description = "부모 메뉴 ID") Long parentId,
        @Schema(description = "메뉴 이름") String name,
        @Schema(description = "상단 네비용 짧은 이름") String shortName,
        @Schema(description = "메뉴 경로") String path,
        @Schema(description = "아이콘") String icon,
        @Schema(description = "부제") String description,
        @Schema(description = "영역") String area,
        @Schema(description = "노드 종류") String menuType,
        @Schema(description = "정렬 순서") int sortOrder,
        @Schema(description = "접근 허용 역할 CSV") String requiredRole,
        @Schema(description = "접근 필요 권한 코드") String requiredPermission,
        @Schema(description = "노출 여부") boolean visible,
        @Schema(description = "활성화 여부") boolean active,
        @Schema(description = "생성일시") LocalDateTime createdAt,
        @Schema(description = "수정일시") LocalDateTime updatedAt,
        @Schema(description = "하위 메뉴 목록 (트리 조회 시 포함)") List<MenuResponse> children
) {
    /** 트리 응답 — children 포함 */
    public static MenuResponse fromTree(Menu menu) {
        List<MenuResponse> childResponses = menu.getChildren().stream()
                .map(MenuResponse::fromTree)
                .collect(Collectors.toList());
        return build(menu, childResponses);
    }

    /** 평면 응답 — children 빈 리스트 */
    public static MenuResponse fromFlat(Menu menu) {
        return build(menu, List.of());
    }

    private static MenuResponse build(Menu menu, List<MenuResponse> children) {
        return new MenuResponse(
                menu.getId(),
                menu.getParentId(),
                menu.getName(),
                menu.getShortName(),
                menu.getPath(),
                menu.getIcon(),
                menu.getDescription(),
                menu.getArea() == null ? null : menu.getArea().name(),
                menu.getType() == null ? null : menu.getType().name(),
                menu.getSortOrder(),
                menu.getRequiredRole(),
                menu.getRequiredPermission(),
                menu.isVisible(),
                menu.isActive(),
                menu.getCreatedAt(),
                menu.getUpdatedAt(),
                children
        );
    }
}
