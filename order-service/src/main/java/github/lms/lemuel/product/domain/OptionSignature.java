package github.lms.lemuel.product.domain;

import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 옵션 선택 조합의 정규화 서명.
 *
 * <p>선택된 {@code (축, 값)} 쌍을 <b>축 id 오름차순</b>으로 정렬해 {@code "axisId:valueId"} 로 적고
 * {@code |} 로 이어 붙인 뒤 SHA-256 을 취한다. 정렬이 규칙의 핵심이다 — 사용자가 색상을 먼저 고르든
 * 사이즈를 먼저 고르든 같은 조합이면 같은 서명이 나와야 하기 때문이다.
 *
 * <p>이 계산은 <b>도메인이 독점한다</b>. 어댑터나 SQL 이 같은 계산을 따로 하면 정렬 규칙이 두 벌이 되고,
 * 그 순간 {@code "색상:빨강/사이즈:L"} 문자열이 조인키였던 시절의 함정 — 순서·구분자 한 끗 차이로 조회가
 * 조용히 실패하는 상태 — 가 그대로 재현된다.
 *
 * <p>서명은 값 <b>이름</b>이 아니라 <b>id</b> 로 만든다. 그래서 "빨강"을 "레드"로 바꿔도 SKU 는 흔들리지 않는다.
 */
public final class OptionSignature {

    private static final String PAIR_SEPARATOR = "|";
    private static final String FIELD_SEPARATOR = ":";
    private static final String ALGORITHM = "SHA-256";

    private OptionSignature() {
    }

    /** 한 차수의 선택 — 표준 축 id 와 표준 값 id. */
    public record AxisSelection(Long axisId, Long axisValueId) {

        public AxisSelection {
            Objects.requireNonNull(axisId, "axisId");
            Objects.requireNonNull(axisValueId, "axisValueId");
        }
    }

    /**
     * 선택 조합 → 64 자 소문자 hex 서명.
     *
     * @throws ProductInvariantViolationException 선택이 비었거나 한 축이 두 번 선택된 경우
     */
    public static String of(Collection<AxisSelection> selections) {
        if (selections == null || selections.isEmpty()) {
            throw new ProductInvariantViolationException("서명을 만들 옵션 선택이 없습니다");
        }
        Set<Long> axes = new HashSet<>();
        for (AxisSelection selection : selections) {
            if (!axes.add(selection.axisId())) {
                throw new ProductInvariantViolationException(
                        "한 축을 두 번 선택할 수 없습니다: axisId=" + selection.axisId());
            }
        }

        String canonical = selections.stream()
                .sorted(Comparator.comparing(AxisSelection::axisId))
                .map(s -> s.axisId() + FIELD_SEPARATOR + s.axisValueId())
                .collect(Collectors.joining(PAIR_SEPARATOR));

        return sha256Hex(canonical);
    }

    /** 편의 오버로드 — 순서쌍 목록으로 직접 계산. */
    public static String of(List<Long> axisIds, List<Long> axisValueIds) {
        if (axisIds == null || axisValueIds == null || axisIds.size() != axisValueIds.size()) {
            throw new ProductInvariantViolationException("축과 값의 개수가 맞지 않습니다");
        }
        List<AxisSelection> selections = new ArrayList<>(axisIds.size());
        for (int i = 0; i < axisIds.size(); i++) {
            selections.add(new AxisSelection(axisIds.get(i), axisValueIds.get(i)));
        }
        return of(selections);
    }

    private static String sha256Hex(String canonical) {
        try {
            byte[] digest = MessageDigest.getInstance(ALGORITHM)
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 은 모든 JRE 필수 구현이라 도달할 수 없다.
            throw new IllegalStateException(ALGORITHM + " 미지원 런타임", e);
        }
    }
}
