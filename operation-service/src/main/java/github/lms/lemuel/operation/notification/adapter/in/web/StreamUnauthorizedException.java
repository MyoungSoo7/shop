package github.lms.lemuel.operation.notification.adapter.in.web;

/** 검증된 신원이 없다 — 401. 500 이 아니다. */
public class StreamUnauthorizedException extends RuntimeException {

    public StreamUnauthorizedException(String message) {
        super(message);
    }
}
