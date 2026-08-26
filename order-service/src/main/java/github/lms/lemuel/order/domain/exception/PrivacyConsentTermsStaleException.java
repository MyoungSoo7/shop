package github.lms.lemuel.order.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 클라이언트가 동의한 문안 버전이 지금 유효한 버전과 다르다.
 *
 * <p><b>낡은 버전을 받아 주면 안 되는 이유</b>: 문안 버전이 올라갔다는 것은 고지 내용이 바뀌었다는
 * 뜻이다. 사용자가 결제 화면을 열어 둔 사이에 버전이 바뀌었다면, 그 사람이 읽은 것은 옛 문안이다.
 * 그 동의를 새 문안에 대한 동의로 기록하면 이력이 거짓이 되고, 옛 문안에 대한 동의로 기록하면
 * 지금 유효한 고지를 받지 않은 채 주문이 성립한다. 둘 다 안 되므로 다시 보여 주고 다시 받는다.
 *
 * <p>모르는 문안 코드도 같은 취급이다. 조용히 무시하면 클라이언트가 잘못된 코드를 보내고 있다는
 * 사실이 아무 데도 남지 않는다.
 */
public class PrivacyConsentTermsStaleException extends BusinessException {

    public PrivacyConsentTermsStaleException(String termsCode, Integer submittedVersion, Integer effectiveVersion) {
        super(ErrorCode.PRIVACY_CONSENT_TERMS_STALE,
                "동의 문안이 변경되었습니다: " + termsCode
                        + " (보낸 버전 " + submittedVersion + ", 현재 버전 " + effectiveVersion + ")",
                details(termsCode, submittedVersion, effectiveVersion));
    }

    /**
     * {@code Map.of} 는 null 값에서 터진다. 버전을 아예 안 보낸 요청도 여기로 오므로
     * (그것도 낡은 화면의 증상이다) 없는 칸은 넣지 않는 편이 안전하다 — 예외를 만들다가
     * 다른 예외가 나면 정작 알려야 할 사유가 사라진다.
     */
    private static Map<String, Object> details(String termsCode, Integer submitted, Integer effective) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("termsCode", termsCode);
        if (submitted != null) {
            details.put("submittedVersion", submitted);
        }
        if (effective != null) {
            details.put("effectiveVersion", effective);
        }
        return Map.copyOf(details);
    }
}
