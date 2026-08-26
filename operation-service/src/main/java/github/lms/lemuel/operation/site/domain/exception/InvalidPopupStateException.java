package github.lms.lemuel.operation.site.domain.exception;

/**
 * 팝업의 현재 상태에서 허용되지 않는 조작. 언체크인 이유는 education 슬라이스와 같다 —
 * 도메인은 스프링(BusinessException)을 의존하지 않으며(ArchUnit 강제), 체크 예외면
 * {@code @Transactional} 이 롤백하지 않는다.
 */
public class InvalidPopupStateException extends RuntimeException {
    public InvalidPopupStateException(String message) {
        super(message);
    }
}
