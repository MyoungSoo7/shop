package github.lms.lemuel.operation.incident.adapter.in.web.dto;

import jakarta.validation.constraints.Size;

/** 전이(ack/resolve/false-positive) 요청 — 메모는 선택. body 자체도 생략 가능. */
public record TransitionRequest(@Size(max = 2000) String note) {

    public static final TransitionRequest EMPTY = new TransitionRequest(null);
}
