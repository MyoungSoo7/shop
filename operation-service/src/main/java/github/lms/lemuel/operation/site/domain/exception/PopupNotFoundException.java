package github.lms.lemuel.operation.site.domain.exception;

import java.util.UUID;

/**
 * 없는 팝업을 가리켰다.
 *
 * <p>전에는 {@code PopupAdminService} 의 중첩 클래스였다. 그러면 예외 하나를 잡으려고
 * 웹 어댑터가 <b>구체 서비스 클래스를 임포트</b>해야 해서, 포트를 아무리 잘 만들어도
 * 어댑터 → 서비스 의존이 남는다. 실제로 {@code SiteExceptionHandler} 가 그 이유 하나로
 * 아키텍처 허용 목록에 올라 있었다.
 */
public class PopupNotFoundException extends RuntimeException {

    public PopupNotFoundException(UUID id) {
        super("popup not found: " + id);
    }
}
