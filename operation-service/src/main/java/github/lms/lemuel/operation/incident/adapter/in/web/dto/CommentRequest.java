package github.lms.lemuel.operation.incident.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 코멘트 요청 — 상태 전이 없는 타임라인 기록이므로 note 필수. */
public record CommentRequest(@NotBlank @Size(max = 2000) String note) {
}
