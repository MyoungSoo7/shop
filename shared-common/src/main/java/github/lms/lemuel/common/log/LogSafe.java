package github.lms.lemuel.common.log;

/**
 * 로그에 실릴 외부 입력을 안전한 형태로 정규화한다 (로그 인젝션 방어).
 *
 * <p>사용자가 제어하는 값을 그대로 로그에 찍으면, 값 안의 개행으로 <b>가짜 로그 줄</b>을 만들어 낼 수 있다.
 * 예: {@code topic = "x\n2026-08-12 INFO  [DLQ replay] operator=admin, replayed=9999"} 를 넘기면
 * 운영자가 없던 사건을 본 것처럼 기록된다. ELK 로 색인되면 대시보드·알람까지 오염된다.
 * 감사 추적이 증거로 쓰이는 도메인이라 "보기 이상한 문자열"이 아니라 <b>증거 위조</b> 문제로 다룬다.
 *
 * <p>정규화 규칙 — 원본을 최대한 보존하되 구조를 깨는 문자만 무력화한다:
 * <ul>
 *   <li>CR/LF/탭 → 공백 1칸 (줄·필드 경계 위조 차단)</li>
 *   <li>그 외 제어문자(ANSI 이스케이프 포함) → {@code ?} (터미널 제어열 주입 차단)</li>
 *   <li>{@link #MAX_LENGTH} 초과분은 잘라내고 {@code …(truncated)} 표시 (로그 폭탄 차단)</li>
 * </ul>
 *
 * <p>이 클래스는 <b>마스킹이 아니다</b>. PII 는 여전히 {@code common.audit} 의 마스킹을 거쳐야 한다 —
 * 여기서는 값의 내용이 아니라 <i>형태</i>만 다룬다.
 */
public final class LogSafe {

    /** 로그 한 필드가 가질 수 있는 최대 길이. 초과분은 잘린다. */
    public static final int MAX_LENGTH = 512;

    private static final String TRUNCATED_SUFFIX = "…(truncated)";

    private LogSafe() {
    }

    /**
     * 외부 입력을 로그에 실을 수 있는 형태로 정규화한다.
     *
     * @param value 원본 값. {@code null} 이면 문자열 {@code "null"} 을 돌려준다(로그에서 null 도 정보다).
     * @return 개행·제어문자가 제거되고 길이가 제한된 문자열
     */
    public static String of(Object value) {
        if (value == null) {
            return "null";
        }
        String raw = value.toString();
        StringBuilder sb = new StringBuilder(Math.min(raw.length(), MAX_LENGTH));
        for (int i = 0; i < raw.length() && sb.length() < MAX_LENGTH; i++) {
            char c = raw.charAt(i);
            if (c == '\r' || c == '\n' || c == '\t') {
                sb.append(' ');
            } else if (Character.isISOControl(c)) {
                sb.append('?');
            } else {
                sb.append(c);
            }
        }
        if (raw.length() > MAX_LENGTH) {
            sb.append(TRUNCATED_SUFFIX);
        }
        return sb.toString();
    }
}
