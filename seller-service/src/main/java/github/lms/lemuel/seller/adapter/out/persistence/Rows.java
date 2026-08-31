package github.lms.lemuel.seller.adapter.out.persistence;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * 네이티브 조회의 {@code Object[]} 한 칸을 자바 타입으로 옮기는 변환기.
 *
 * <p>네이티브 쿼리를 인터페이스 프로젝션이 아니라 {@code Object[]} 로 받는 이유는, 인터페이스
 * 프로젝션이 <b>컬럼 별칭과 게터 이름을 문자열로</b> 맞추기 때문이다. 이름이 어긋나면 컴파일도
 * 통과하고 기동도 성공하고 조회 시점에만 터진다 — 그 시점의 사용자는 셀러다. 여기서는
 * 위치로 읽고, 어긋나면 이 클래스의 캐스팅에서 즉시 터진다.
 *
 * <p>드라이버·Hibernate 버전에 따라 같은 컬럼이 {@code java.sql.Timestamp} 로도
 * {@code LocalDateTime} 으로도 온다. 둘 다 받는 것은 방어가 아니라 사실이다 — 한쪽만 받으면
 * 의존성 올릴 때 조용히 깨진다.
 */
final class Rows {

    private Rows() {
    }

    static long longAt(Object[] row, int index) {
        Object value = row[index];
        if (value == null) {
            throw new IllegalStateException("NOT NULL 컬럼이 null 로 왔다 (index=" + index + ")");
        }
        return ((Number) value).longValue();
    }

    static Long nullableLongAt(Object[] row, int index) {
        Object value = row[index];
        return value == null ? null : ((Number) value).longValue();
    }

    static String stringAt(Object[] row, int index) {
        Object value = row[index];
        return value == null ? null : value.toString();
    }

    static boolean boolAt(Object[] row, int index) {
        Object value = row[index];
        return value != null && (Boolean) value;
    }

    /**
     * 금액. null 이면 0 이 아니라 예외다 — 합계 쿼리는 전부 {@code COALESCE} 로 감싸 두었으므로
     * 여기 null 이 온다는 것은 쿼리가 바뀌었다는 뜻이고, 그걸 0 으로 삼키면 "매출 0" 이라는
     * 그럴듯한 거짓말이 화면에 뜬다.
     */
    static BigDecimal decimalAt(Object[] row, int index) {
        Object value = row[index];
        if (value == null) {
            throw new IllegalStateException("금액 컬럼이 null 로 왔다 (index=" + index + ")");
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    /**
     * TIMESTAMPTZ 컬럼. {@code Timestamp} 로 오는 경우 {@code toInstant()} 를 거치는 것이
     * 중요하다 — {@code toLocalDateTime()} 은 JVM 기본 존으로 해석해 버려서, 같은 값이
     * 노드마다 다른 시각이 된다.
     */
    static OffsetDateTime offsetDateTimeAt(Object[] row, int index) {
        Object value = row[index];
        if (value == null) {
            return null;
        }
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        return ((Timestamp) value).toInstant().atOffset(ZoneOffset.UTC);
    }

    static LocalDateTime dateTimeAt(Object[] row, int index) {
        Object value = row[index];
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        return ((Timestamp) value).toLocalDateTime();
    }
}
