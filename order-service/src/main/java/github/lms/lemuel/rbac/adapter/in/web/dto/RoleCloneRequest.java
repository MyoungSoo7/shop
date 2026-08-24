package github.lms.lemuel.rbac.adapter.in.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "역할 복제 요청 — 원본의 권한 매핑까지 복사한다")
public record RoleCloneRequest(
        @Schema(description = "새 역할 코드 (대문자 시작, 대문자/숫자/언더스코어 2~30자)", example = "CS_AGENT_JR")
        @NotBlank @Size(max = 30) String code,

        @Schema(description = "새 역할 이름 (생략 시 \"원본이름 (복제)\")", example = "주니어 CS 상담원")
        @Size(max = 100) String name
) {}
