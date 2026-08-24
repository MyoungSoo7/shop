package github.lms.lemuel.menu.adapter.in.web.dto;

import github.lms.lemuel.menu.domain.MenuArea;
import github.lms.lemuel.menu.domain.MenuAttributes;
import github.lms.lemuel.menu.domain.MenuType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Schema(description = "메뉴 수정 요청")
public class MenuUpdateRequest {

    @NotBlank(message = "메뉴 이름은 필수입니다.")
    @Size(max = 100, message = "메뉴 이름은 100자 이하여야 합니다.")
    @Schema(description = "메뉴 이름", example = "사용자 관리")
    private String name;

    @Size(max = 40, message = "짧은 이름은 40자 이하여야 합니다.")
    @Schema(description = "상단 네비용 짧은 이름", example = "사용자")
    private String shortName;

    @Size(max = 255, message = "경로는 255자 이하여야 합니다.")
    @Schema(description = "메뉴 경로", example = "/admin/users")
    private String path;

    @Size(max = 50, message = "아이콘은 50자 이하여야 합니다.")
    @Schema(description = "아이콘", example = "👤")
    private String icon;

    @Size(max = 200, message = "부제는 200자 이하여야 합니다.")
    @Schema(description = "부제", example = "회원 조회 · 정지")
    private String description;

    @NotNull(message = "메뉴 영역은 필수입니다.")
    @Schema(description = "소속 영역", example = "SYSTEM")
    private MenuArea area;

    @Schema(description = "노드 종류", example = "ITEM")
    private MenuType menuType = MenuType.ITEM;

    @Schema(description = "부모 메뉴 ID (최상위이면 null)")
    private Long parentId;

    @Schema(description = "정렬 순서", example = "0")
    private int sortOrder;

    @Size(max = 60, message = "역할 목록은 60자 이하여야 합니다.")
    @Schema(description = "접근 허용 역할 CSV", example = "ADMIN,MANAGER")
    private String requiredRole;

    @Size(max = 60, message = "권한 코드는 60자 이하여야 합니다.")
    @Schema(description = "접근 필요 권한 코드", example = "SYSTEM_MENU_MANAGE")
    private String requiredPermission;

    @Schema(description = "노출 여부", example = "true")
    private boolean visible = true;

    @Schema(description = "활성화 여부", example = "true")
    private boolean active = true;

    public MenuAttributes toAttributes() {
        return new MenuAttributes(name, shortName, path, icon, description,
                area, menuType, requiredRole, requiredPermission);
    }
}
