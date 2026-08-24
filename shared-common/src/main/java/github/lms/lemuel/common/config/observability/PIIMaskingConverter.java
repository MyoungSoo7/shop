package github.lms.lemuel.common.config.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.pattern.CompositeConverter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로그 메시지의 PII 를 마스킹하는 logback composite converter.
 *
 * <p>logback-spring.xml 에서 conversionRule 로 등록한 뒤 패턴에 {@code %mask(%msg)} 사용:
 * <pre>{@code
 *   <conversionRule conversionWord="mask"
 *       class="github.lms.lemuel.common.config.observability.PIIMaskingConverter"/>
 * }</pre>
 *
 * <p>마스킹 대상:
 * <ul>
 *   <li>이메일: {@code user@example.com} → {@code u***@e***.com}</li>
 *   <li>전화: {@code 010-1234-5678} → {@code 010-****-5678}</li>
 *   <li>카드: {@code 1234-5678-9012-3456} → {@code 123456*********3456}</li>
 * </ul>
 *
 * <p>{@link CompositeConverter} 를 상속하므로 child 패턴(예: {@code %msg})의 출력에 대해
 * 마스킹을 후처리한다.
 */
public class PIIMaskingConverter extends CompositeConverter<ILoggingEvent> {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("([A-Za-z0-9._%+-])[A-Za-z0-9._%+-]*@([A-Za-z0-9.-])[A-Za-z0-9.-]*(\\.[A-Za-z]{2,})");

    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(01[016789])-?(\\d{3,4})-?(\\d{4})");

    /**
     * 카드번호: 13~19자리 연속 숫자 또는 하이픈/공백 구분.
     * 앞 6자리(BIN) + 중간 마스크 + 뒤 4자리 노출 — PCI DSS 준수 방향.
     */
    private static final Pattern CARD_PATTERN =
            Pattern.compile("\\b(\\d{4}[- ]?\\d{4}[- ]?\\d{4}[- ]?\\d{4})\\b");

    @Override
    protected String transform(ILoggingEvent event, String in) {
        return mask(in);
    }

    public static String mask(String input) {
        if (input == null || input.isEmpty()) return input;
        String result = input;
        result = maskEmails(result);
        result = maskPhones(result);
        result = maskCards(result);
        return result;
    }

    private static String maskEmails(String s) {
        Matcher m = EMAIL_PATTERN.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(1) + "***@" + m.group(2) + "***" + m.group(3);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String maskPhones(String s) {
        Matcher m = PHONE_PATTERN.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String replacement = m.group(1) + "-****-" + m.group(3);
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static String maskCards(String s) {
        Matcher m = CARD_PATTERN.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String raw = m.group(1).replaceAll("[- ]", "");
            if (raw.length() < 13 || raw.length() > 19) {
                m.appendReplacement(out, Matcher.quoteReplacement(m.group(0)));
                continue;
            }
            String bin = raw.substring(0, 6);
            String last = raw.substring(raw.length() - 4);
            String masked = bin + "*".repeat(raw.length() - 10) + last;
            m.appendReplacement(out, Matcher.quoteReplacement(masked));
        }
        m.appendTail(out);
        return out.toString();
    }
}
