package github.lms.lemuel.operation.notification.application;

/**
 * 채널 1건의 전달 결과. sealed 라 호출자가 성공/실패를 전수 처리해야 한다
 * (새 결과 종류가 생기면 switch 가 컴파일 에러로 알려준다).
 */
public sealed interface ChannelResult permits ChannelResult.Success, ChannelResult.Failure {

    /** 안정적인 채널 이름 — "log", "slack", "email", "sse". */
    String channel();

    /** 이 결과에 도달하기까지 소모한 시도 횟수. */
    int attempts();

    record Success(String channel, int attempts) implements ChannelResult {
    }

    record Failure(String channel, int attempts, String error) implements ChannelResult {
    }
}
