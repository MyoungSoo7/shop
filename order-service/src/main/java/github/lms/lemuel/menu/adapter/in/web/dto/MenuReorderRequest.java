package github.lms.lemuel.menu.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@Schema(description = "메뉴 배치 재배치 요청 — 여러 메뉴의 부모/정렬순서를 한 번에 저장")
public record MenuReorderRequest(
        @NotEmpty @Valid List<Item> items
) {
    @Schema(description = "재배치 항목")
    public record Item(
            @Schema(description = "메뉴 ID") @NotNull Long id,
            @Schema(description = "새 부모 메뉴 ID (null 이면 최상위)") Long parentId,
            @Schema(description = "새 정렬 순서") int sortOrder
    ) {}
}
