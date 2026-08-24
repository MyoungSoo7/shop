package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 레거시 표시 규약 {@code "색상:빨강/사이즈:L"} 의 파서/포매터.
 *
 * <p>이 문자열은 원래 SKU 조회의 <b>조인키</b>였다 — 구분자·순서·공백 중 하나만 어긋나도 조회가
 * 조용히 실패하는 구조였다. 카탈로그 테이블이 정본이 된 뒤로 이 클래스의 용도는 두 가지뿐이다:
 * <ol>
 *   <li>백필 — 기존 {@code product_variants.option_name} 에서 축/값을 역생성</li>
 *   <li>표시 — 축·값 ID 로 조립한 사람이 읽는 라벨 생성</li>
 * </ol>
 *
 * <p>파싱은 <b>관대하지 않다</b>. 세그먼트에 {@code :} 가 정확히 하나가 아니면 예외를 던진다.
 * 첫 번째 콜론으로 나누는 관대한 파싱은 {@code "각인:A:B"} 같은 데이터를 조용히 잘못 해석해
 * 백필 결과를 오염시키므로, 이상 데이터는 백필 시점에 드러나는 편이 낫다.
 */
public final class LegacyOptionName {

    private static final String AXIS_SEPARATOR = "/";
    private static final String VALUE_SEPARATOR = ":";

    private LegacyOptionName() {
    }

    /** 파싱 결과 한 차수 — 축 이름과 값 이름. */
    public record Segment(String axisName, String valueName) {

        public Segment {
            if (axisName == null || axisName.isBlank()) {
                throw new ProductInvariantViolationException("옵션 축 이름이 비어 있습니다");
            }
            if (valueName == null || valueName.isBlank()) {
                throw new ProductInvariantViolationException("옵션 값 이름이 비어 있습니다");
            }
            axisName = axisName.trim();
            valueName = valueName.trim();
        }
    }

    /**
     * {@code "색상:빨강/사이즈:L"} → {@code [(색상,빨강), (사이즈,L)]}. 순서는 차수 순서 그대로 보존한다.
     *
     * @throws ProductInvariantViolationException 빈 문자열, {@code :} 개수 이상, 축 이름 중복
     */
    public static List<Segment> parse(String optionName) {
        if (optionName == null || optionName.isBlank()) {
            throw new ProductInvariantViolationException("옵션 표시명이 비어 있습니다");
        }

        List<Segment> segments = new ArrayList<>();
        Set<String> seenAxes = new LinkedHashSet<>();

        for (String raw : optionName.split(AXIS_SEPARATOR, -1)) {
            String part = raw.trim();
            if (part.isEmpty()) {
                throw new ProductInvariantViolationException(
                        "옵션 표시명에 빈 차수가 있습니다: " + optionName);
            }
            int colons = countOccurrences(part, VALUE_SEPARATOR);
            if (colons != 1) {
                throw new ProductInvariantViolationException(
                        "차수는 '축:값' 형식이어야 합니다(':' 1 개): " + part);
            }
            int idx = part.indexOf(VALUE_SEPARATOR);
            Segment segment = new Segment(part.substring(0, idx), part.substring(idx + 1));
            if (!seenAxes.add(segment.axisName())) {
                throw new ProductInvariantViolationException(
                        "같은 축이 두 번 나타납니다: " + segment.axisName());
            }
            segments.add(segment);
        }
        return List.copyOf(segments);
    }

    /** 차수 목록 → 표시명. {@link #parse(String)} 와 왕복(round-trip) 가능해야 한다. */
    public static String format(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) {
            throw new ProductInvariantViolationException("표시명을 만들 차수가 없습니다");
        }
        return segments.stream()
                .map(s -> s.axisName() + VALUE_SEPARATOR + s.valueName())
                .collect(Collectors.joining(AXIS_SEPARATOR));
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int from = 0;
        int idx;
        while ((idx = text.indexOf(token, from)) >= 0) {
            count++;
            from = idx + token.length();
        }
        return count;
    }
}
