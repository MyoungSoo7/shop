package github.lms.lemuel.giftcard.domain;

import github.lms.lemuel.giftcard.domain.exception.InvalidGiftCardStateException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 기프트카드 코드 — 생성·정규화·해시.
 *
 * <p><b>평문은 저장되지 않는다.</b> 발행 응답에서 한 번만 나가고, 그 뒤로는 해시만 남는다.
 * 그래서 이 클래스가 하는 일은 셋뿐이다: 충분한 엔트로피로 만들고, 사용자가 어떻게 입력하든
 * 같은 값으로 접고, 조회 키가 될 해시를 만든다.
 *
 * <p><b>솔트를 쓰지 않는 이유</b>: 코드 엔트로피가 16자 × 32진 ≈ 80비트라 사전 공격이 성립하지
 * 않는다. 반면 솔트를 쓰면 등록 시 코드로 행을 찾기 위해 전 행과 대조해야 한다 — 보안 이득 없이
 * 조회만 O(n) 이 된다. (비밀번호와 다른 상황이다. 비밀번호는 엔트로피가 낮아 솔트가 필수다.)
 *
 * <p>글자 집합은 Crockford Base32 — 사람이 받아 적는 코드라 {@code I·L·O·U} 를 뺀다.
 */
public final class GiftCardCode {

    private static final String PREFIX = "GC-";
    /** Crockford Base32 — 혼동하기 쉬운 I·L·O·U 제외. */
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int BODY_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private GiftCardCode() {
    }

    /** 새 코드 — {@code GC-} + 32진 16자(≈80비트). */
    public static String generate() {
        StringBuilder body = new StringBuilder(BODY_LENGTH);
        for (int i = 0; i < BODY_LENGTH; i++) {
            body.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return PREFIX + body;
    }

    /** 조회·저장 키. 같은 코드는 입력 형태가 달라도 같은 해시로 접힌다. */
    public static String hashOf(String rawCode) {
        byte[] digest = sha256(normalize(rawCode));
        return HexFormat.of().formatHex(digest);
    }

    /** 화면 표시용 뒤 4자리. 전체 코드는 어디에도 다시 나타나지 않는다. */
    public static String last4(String rawCode) {
        String normalized = normalize(rawCode);
        return normalized.length() <= 4 ? normalized : normalized.substring(normalized.length() - 4);
    }

    /**
     * 정규화 — 대문자화 + 구분자 제거. 사용자는 하이픈을 넣기도 빼기도 하고 소문자로 적기도 한다.
     * 그 차이가 "코드가 틀렸다"는 답으로 돌아가면 지원 비용이 된다.
     */
    private static String normalize(String rawCode) {
        if (rawCode == null || rawCode.isBlank()) {
            throw new InvalidGiftCardStateException("코드가 비어 있습니다", "NONE", "code");
        }
        return rawCode.trim().toUpperCase(java.util.Locale.ROOT).replaceAll("[\\s-]", "");
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 은 모든 JRE 필수 알고리즘이다 — 여기 오면 런타임이 깨진 것이다.
            throw new IllegalStateException("SHA-256 을 사용할 수 없습니다", exception);
        }
    }
}
