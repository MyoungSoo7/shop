package github.lms.lemuel.rbac.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "역할 수정 요청 (코드는 불변)")
public record RoleUpdateRequest(
        @Schema(description = "역할 이름", example = "CS 상담원")
        @NotBlank @Size(max = 100) String name,

        @Schema(description = "역할 설명", example = "고객 문의 대응 상담원")
        @Size(max = 255) String description
) {}
