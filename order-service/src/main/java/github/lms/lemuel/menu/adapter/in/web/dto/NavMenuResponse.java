package github.lms.lemuel.menu.adapter.in.web.dto;

import github.lms.lemuel.menu.domain.Menu;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * 셸 네비게이션 전용 응답 — 프론트가 상단 네비/사이드바를 그리는 데 필요한 것만 담는다.
 *
 * <p>관리 화면용 {@link MenuResponse} 와 분리한 이유: 관리 DTO 에 필드가 늘어도 셸 계약은
 * 흔들리지 않아야 한다. 여기 담기지 않은 값(정렬순서·권한·타임스탬프)은 서버가 이미 반영해
 * 내려보낸 결과이므로 클라이언트가 다시 알 필요가 없다.
 */
@Schema(description = "네비게이션 메뉴 노드")
public record NavMenuResponse(
        @Schema(description = "메뉴 ID") Long id,
        @Schema(description = "메뉴 이름 (사이드바 머리글)") String name,
        @Schema(description = "표시 라벨 (상단 네비 — shortName 우선)") String label,
        @Schema(description = "착지 경로 (구분선이면 null)") String path,
        @Schema(description = "아이콘") String icon,
        @Schema(description = "부제") String description,
        @Schema(description = "영역") String area,
        @Schema(description = "노드 종류 (GROUP/ITEM/DIVIDER)") String type,
        @Schema(description = "하위 메뉴") List<NavMenuResponse> children
) {

    public static NavMenuResponse from(Menu menu) {
        return new NavMenuResponse(
                menu.getId(),
                menu.getName(),
                menu.displayLabel(),
                menu.getPath(),
                menu.getIcon(),
                menu.getDescription(),
                menu.getArea() == null ? null : menu.getArea().name(),
                menu.getType() == null ? null : menu.getType().name(),
                menu.getChildren().stream().map(NavMenuResponse::from).toList()
        );
    }
}
