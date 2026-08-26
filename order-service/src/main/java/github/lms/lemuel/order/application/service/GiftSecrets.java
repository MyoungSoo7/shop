package github.lms.lemuel.order.application.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 선물 링크 토큰과 인증번호를 만들고 해시한다.
 *
 * <p><b>왜 해시로 저장하는가.</b> 링크 토큰은 로그인 없이 남의 주문 화면을 여는 열쇠다. 평문으로
 * 두면 DB 한 벌이 새는 순간 살아 있는 링크가 전부 즉시 쓸 수 있는 상태가 된다. 같은 저장소의
 * {@code PasswordResetToken} 은 평문을 저장하는데, 그 자리를 따라가지 않는다.
 *
 * <p><b>인증번호는 6자리라 해시만으로는 못 지킨다</b> — 유출된 해시 하나를 100만 번 시도하면
 * 되돌아온다. 그래서 두 가지를 겹친다: 토큰 해시를 소금으로 섞어 표(rainbow table)를 무의미하게
 * 만들고, 실제 방어는 도메인의 시도 횟수 제한과 짧은 유효시간이 한다. 여기서 해시는
 * "쿼리 로그·백업에 6자리가 그대로 남지 않게" 하는 몫까지다 — 그 이상을 주장하지 않는다.
 */
final class GiftSecrets {

    /** 링크 토큰의 엔트로피. 256비트면 온라인 추측은 물론 오프라인 열거도 성립하지 않는다. */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private GiftSecrets() {
    }

    /** URL 에 그대로 들어가는 평문 토큰. 부르는 쪽이 곧바로 흘려보내고 잊어야 한다. */
    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /**
     * 6자리 인증번호. 앞자리 0 을 허용한다 — 100000~999999 로 좁히면 경우의 수가 10% 줄고,
     * 사람이 "여섯 자리"라고 읽는 것과도 어긋난다.
     */
    static String newVerificationCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    static String hashToken(String token) {
        return sha256Hex(token);
    }

    /** 인증번호는 그 링크에 묶인다 — 소금이 없으면 6자리 해시가 전 행에서 같은 값이 된다. */
    static String hashCode(String tokenHash, String code) {
        return sha256Hex(tokenHash + ':' + code);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 은 JDK 필수 알고리즘이라 여기 오지 않는다. 체크 예외를 밖으로 흘리지 않는다.
            throw new IllegalStateException("SHA-256 을 쓸 수 없습니다", exception);
        }
    }
}
