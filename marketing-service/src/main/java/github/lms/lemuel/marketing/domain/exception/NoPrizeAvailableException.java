package github.lms.lemuel.marketing.domain.exception;

/**
 * 뽑을 수 있는 경품이 없다 — 활성 경품이 없거나 전부 수량이 소진됐다.
 *
 * <p>레거시에는 이 경로가 없었다. 수량 확인 자체가 구현되지 않은 채 주석만 남아 있어서,
 * 재고가 0 인 경품도 계속 당첨됐다.
 */
public class NoPrizeAvailableException extends RuntimeException {
    public NoPrizeAvailableException(String message) {
        super(message);
    }
}
