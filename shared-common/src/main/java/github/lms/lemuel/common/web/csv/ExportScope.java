package github.lms.lemuel.common.web.csv;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * CSV 가 <b>전체인지 일부인지</b>를 말하는 값. {@link CsvResponse#of} 의 필수 인자다.
 *
 * <h2>왜 필수 인자인가</h2>
 * 전에는 {@code X-Export-Truncated} · {@code X-Export-Total} 두 줄을 컨트롤러마다 손으로 붙였고,
 * 여섯 곳에 같은 코드가 복사돼 있었다. 그 방식의 문제는 <b>빠뜨렸을 때 아무 일도 안 일어난다</b>는
 * 것이다 — 헤더 없는 CSV 는 열리고, 행도 들어 있고, 잘렸다는 말만 없다. 받은 사람은 5,000 행짜리
 * 파일을 전량으로 믿고 정산·감사에 쓴다. 컴파일러도 테스트도 "없는 헤더"는 못 본다.
 *
 * <p>그래서 헤더를 <b>붙이는 것</b>을 공통화하는 대신 <b>말하는 것</b>을 강제한다. 이 인자가
 * 없으면 CSV 자체가 만들어지지 않으므로, 새 export 가 조용히 침묵할 방법이 없다.
 */
public final class ExportScope {

    /** 잘렸는지 여부. 받은 사람이 이 파일을 전량으로 믿어도 되는가. */
    public static final String HEADER_TRUNCATED = "X-Export-Truncated";

    /** 조건에 맞는 전체 건수. 담긴 행 수와 다를 수 있다. */
    public static final String HEADER_TOTAL = "X-Export-Total";

    /** 담을 수 있는 상한. 전체 건수를 모르는 export 만 쓴다. */
    public static final String HEADER_LIMIT = "X-Export-Limit";

    private static final String HEADER_PREFIX = "X-Export-";

    private final Long totalElements;
    private final Boolean truncated;
    private final Integer limit;
    private final Map<String, String> extraHeaders;

    private ExportScope(Long totalElements, Boolean truncated, Integer limit,
                        Map<String, String> extraHeaders) {
        this.totalElements = totalElements;
        this.truncated = truncated;
        this.limit = limit;
        this.extraHeaders = extraHeaders;
    }

    /**
     * 전체 건수를 아는 export — 목록 조회처럼 {@code count} 를 같이 세는 경우.
     *
     * @param totalElements 조건에 맞는 전체 건수(담긴 행 수가 아니다)
     * @param truncated     상한에 걸려 일부만 담겼는가
     */
    public static ExportScope of(long totalElements, boolean truncated) {
        if (totalElements < 0) {
            throw new IllegalArgumentException("전체 건수는 음수일 수 없습니다: " + totalElements);
        }
        return new ExportScope(totalElements, truncated, null, Map.of());
    }

    /**
     * 상한까지만 담았고 <b>전체 건수는 모르는</b> export — 랭킹처럼 본래 상위 N개만 뽑는 경우.
     *
     * <p>{@link #of} 에 담긴 행 수를 전체 건수인 척 넘기지 않기 위해 따로 둔다. 그렇게 하면
     * "20개 중 20개"라는 헤더가 나가는데, 실제로는 200개 중 20개일 수 있다.
     *
     * <p>잘렸는지는 담긴 행 수가 상한에 닿았는지로 판정한다 — 상한과 정확히 같은 수가 존재하는
     * 경우는 구분할 수 없으므로 <b>잘렸다고 본다</b>. 안 잘린 것을 잘렸다고 말하는 쪽이 반대보다 낫다.
     */
    public static ExportScope limited(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("상한은 1 이상이어야 합니다: " + limit);
        }
        return new ExportScope(null, null, limit, Map.of());
    }

    /**
     * 부가 헤더를 얹는다(기간·합계처럼 그 export 에만 있는 맥락).
     *
     * @throws IllegalArgumentException 이름이 {@code X-Export-} 로 시작하지 않거나,
     *                                  위 세 헤더를 덮어쓰려 할 때. 덮어쓰기를 허용하면
     *                                  이 클래스를 우회해 "안 잘렸다"고 말할 수 있게 된다
     */
    public ExportScope with(String headerName, String value) {
        if (headerName == null || !headerName.startsWith(HEADER_PREFIX)) {
            throw new IllegalArgumentException("부가 헤더는 " + HEADER_PREFIX + " 로 시작해야 합니다: " + headerName);
        }
        if (HEADER_TRUNCATED.equalsIgnoreCase(headerName)
                || HEADER_TOTAL.equalsIgnoreCase(headerName)
                || HEADER_LIMIT.equalsIgnoreCase(headerName)) {
            throw new IllegalArgumentException("예약된 헤더는 덮어쓸 수 없습니다: " + headerName);
        }
        Map<String, String> merged = new LinkedHashMap<>(extraHeaders);
        merged.put(headerName, value == null ? "" : value);
        return new ExportScope(totalElements, truncated, limit, Map.copyOf(merged));
    }

    /**
     * 실제로 담긴 행 수를 받아 헤더를 확정한다.
     *
     * @param exportedRows CSV 에 담긴 데이터 행 수(헤더 행 제외)
     */
    Map<String, String> toHeaders(int exportedRows) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (limit != null) {
            headers.put(HEADER_TRUNCATED, String.valueOf(exportedRows >= limit));
            headers.put(HEADER_LIMIT, String.valueOf(limit));
        } else {
            headers.put(HEADER_TRUNCATED, String.valueOf(truncated));
            headers.put(HEADER_TOTAL, String.valueOf(totalElements));
        }
        headers.putAll(extraHeaders);
        return headers;
    }
}
