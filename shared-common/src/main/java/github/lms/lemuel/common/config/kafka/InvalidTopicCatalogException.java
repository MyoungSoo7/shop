package github.lms.lemuel.common.config.kafka;

/**
 * 토픽 카탈로그가 불변식을 위반했을 때 던진다 — 기동 시점에 즉시 실패한다.
 *
 * <p>generic {@code IllegalArgumentException} 대신 타입 예외를 쓰는 이유는 이 저장소의 예외 설계
 * 규율과 같다: 호출자가 "무엇이 잘못됐는지"를 타입으로 구분할 수 있어야 한다. 특히 이 예외는
 * 컨슈머 런타임의 non-retryable 판정(IAE/ISE)과 섞이면 안 된다 — 성격이 다른 기동 실패다.
 */
public class InvalidTopicCatalogException extends RuntimeException {

    public InvalidTopicCatalogException(String message) {
        super(message);
    }

    public InvalidTopicCatalogException(String message, Throwable cause) {
        super(message, cause);
    }
}
