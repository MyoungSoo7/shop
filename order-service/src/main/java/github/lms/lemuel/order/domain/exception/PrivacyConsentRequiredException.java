package github.lms.lemuel.order.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

import java.util.List;
import java.util.Map;

/**
 * 필수 동의를 받지 못한 채로 주문을 만들려 했다.
 *
 * <p>화면에서 체크박스를 강제하면 되지 않느냐는 반문이 나오지만, 화면의 강제는 화면에서만 성립한다.
 * 주문 생성은 HTTP 로 열려 있으므로 체크박스를 거치지 않고 부르는 경로가 언제나 존재한다. 동의를
 * 서버에서 막지 않으면 "받았다"고 말할 근거가 없는 주문이 조용히 쌓인다.
 *
 * <p>어떤 항목이 빠졌는지는 {@code details} 로 돌려준다 — 숨길 정보가 아니고, 클라이언트가 그
 * 항목을 다시 보여 주어야 사용자가 진행할 수 있다.
 */
public class PrivacyConsentRequiredException extends BusinessException {

    public PrivacyConsentRequiredException(List<String> missingTermsCodes) {
        super(ErrorCode.PRIVACY_CONSENT_REQUIRED,
                "필수 동의 항목에 동의해야 주문할 수 있습니다: " + String.join(", ", missingTermsCodes),
                Map.of("missingTermsCodes", List.copyOf(missingTermsCodes)));
    }
}
