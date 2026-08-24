package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 옵션 파셋 필터 — "색상=빨강 또는 파랑, 그리고 사이즈=L".
 *
 * <p><b>의미 규칙은 두 줄이고, 이 클래스가 그 정본이다.</b>
 * <ul>
 *   <li><b>같은 축 안에서는 OR</b> — 색상에 빨강·파랑을 고르면 둘 중 하나면 된다(선택을 넓히는 행위).</li>
 *   <li><b>축과 축 사이는 AND</b> — 색상과 사이즈를 함께 고르면 둘 다 만족해야 한다(좁히는 행위).</li>
 * </ul>
 *
 * <p>여기서 흔히 나는 사고: AND 를 <b>상품</b> 단위로 걸면 "빨강 SKU 가 있고, 따로 L SKU 도 있는" 상품이
 * 빨강+L 검색에 걸린다. 실제로는 빨강 L 을 살 수 없는데 결과에 나온다. 그래서 AND 는 반드시
 * <b>SKU 하나</b> 안에서 성립해야 하며, 조회 계층은 이 규칙을 SKU 단위로 집계해 지킨다.
 *
 * <p>토큰 형식은 {@code "축:값"} 이고 코드 정규화는 {@link OptionCode} 규칙을 그대로 쓴다 —
 * 화면이 표시명을 보내든 코드를 보내든 같은 축·값을 가리키게 하기 위해서다.
 */
public final class OptionFacetQuery {

    private static final String TOKEN_SEPARATOR = ":";

    /** 축 코드 → 값 코드 집합. 입력 순서를 보존한다(응답의 축 순서가 요청과 어긋나지 않게). */
    private final Map<String, Set<String>> selections;

    private OptionFacetQuery(Map<String, Set<String>> selections) {
        this.selections = selections;
    }

    public static OptionFacetQuery empty() {
        return new OptionFacetQuery(Map.of());
    }

    /**
     * {@code ["색상:빨강", "색상:파랑", "사이즈:L"]} → 색상{빨강,파랑} AND 사이즈{L}.
     *
     * @throws ProductInvariantViolationException 토큰이 {@code 축:값} 형식이 아닌 경우
     */
    public static OptionFacetQuery of(Collection<String> tokens) {
        if (tokens == null || tokens.isEmpty()) {
            return empty();
        }
        Map<String, Set<String>> parsed = new LinkedHashMap<>();
        for (String raw : tokens) {
            if (raw == null || raw.isBlank()) {
                throw new ProductInvariantViolationException("빈 옵션 필터 토큰이 있습니다");
            }
            String token = raw.trim();
            int colons = token.length() - token.replace(TOKEN_SEPARATOR, "").length();
            if (colons != 1) {
                throw new ProductInvariantViolationException(
                        "옵션 필터는 '축:값' 형식이어야 합니다(':' 1 개): " + token);
            }
            int idx = token.indexOf(TOKEN_SEPARATOR);
            String axisCode = OptionCode.fromDisplayName(token.substring(0, idx), "옵션 축");
            String valueCode = OptionCode.fromDisplayName(token.substring(idx + 1), "옵션 값");
            parsed.computeIfAbsent(axisCode, k -> new LinkedHashSet<>()).add(valueCode);
        }
        return new OptionFacetQuery(parsed);
    }

    public boolean isEmpty() {
        return selections.isEmpty();
    }

    /** 선택된 축 수 — SKU 하나가 이 수만큼의 축을 모두 만족해야 한다(축 간 AND). */
    public int axisCount() {
        return selections.size();
    }

    public Set<String> axisCodes() {
        return new LinkedHashSet<>(selections.keySet());
    }

    public Set<String> valueCodesOf(String axisCode) {
        return new LinkedHashSet<>(selections.getOrDefault(axisCode, Set.of()));
    }

    /** {@code (축, 값)} 쌍 목록 — 조회 계층이 IN 절로 펴서 쓴다. */
    public List<AxisValue> pairs() {
        List<AxisValue> flat = new ArrayList<>();
        selections.forEach((axis, values) -> values.forEach(v -> flat.add(new AxisValue(axis, v))));
        return List.copyOf(flat);
    }

    /**
     * 한 축의 선택만 뺀 질의.
     *
     * <p>파셋 개수를 셀 때 필요하다: 색상=빨강을 고른 상태에서 색상 파셋의 개수를 <b>모든</b> 선택을
     * 적용해 세면 파랑이 0 이 되어 화면에서 사라진다. 사용자는 빨강에 파랑을 <b>추가</b>할 수 없게 된다.
     * 그래서 각 축의 개수는 "자기 축 선택을 뺀" 조건으로 센다.
     */
    public OptionFacetQuery without(String axisCode) {
        if (!selections.containsKey(axisCode)) {
            return this;
        }
        Map<String, Set<String>> remaining = new LinkedHashMap<>(selections);
        remaining.remove(axisCode);
        return new OptionFacetQuery(remaining);
    }

    /** 한 차수의 선택 값. */
    public record AxisValue(String axisCode, String valueCode) {
    }
}
