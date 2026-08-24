package github.lms.lemuel.operation.notification.adapter.in.web;

/** 서명 키가 설정되지 않았다 — 스트림은 신뢰로 데이터를 내주는 대신 서빙을 거부한다(503). */
public class StreamNotConfiguredException extends RuntimeException {

    public StreamNotConfiguredException(String message) {
        super(message);
    }
}
