package github.lms.lemuel.common.web.csv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CSV 내보내기 유틸 테스트.
 *
 * <p>여기서 지키는 것은 두 가지 사고 방지다 — 한글 깨짐(BOM)과 셀 수식 실행(injection).
 * 둘 다 "대체로 잘 동작하다가" 특정 데이터에서만 드러나는 종류라 단위 테스트로 못박는다.
 */
class CsvResponseTest {

    private record Row(String name, String memo) {
    }

    private static ResponseEntity<ByteArrayResource> export(List<Row> rows) {
        return export(rows, ExportScope.of(rows.size(), false));
    }

    private static ResponseEntity<ByteArrayResource> export(List<Row> rows, ExportScope scope) {
        return CsvResponse.of("members", List.of("이름", "메모"), rows,
                r -> List.of(r.name(), r.memo()), scope);
    }

    private static Row row(String name) {
        return new Row(name, "메모");
    }

    private static String bodyOf(ResponseEntity<ByteArrayResource> response) {
        return new String(response.getBody().getByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("본문은 UTF-8 BOM 으로 시작한다 — 없으면 Excel 이 CP949 로 읽어 한글이 깨진다")
    void startsWithUtf8Bom() {
        String body = bodyOf(export(List.of(new Row("홍길동", "정상"))));

        assertThat(body).startsWith("﻿");
        assertThat(body).contains("홍길동");
    }

    @Test
    @DisplayName("모든 셀을 따옴표로 감싸고, 내부 따옴표는 두 번 겹쳐 이스케이프한다")
    void alwaysQuotesAndEscapes() {
        String body = bodyOf(export(List.of(new Row("김\"철수\"", "쉼표, 포함"))));

        assertThat(body).contains("\"김\"\"철수\"\"\",\"쉼표, 포함\"");
    }

    @Test
    @DisplayName("= + - @ 로 시작하는 값은 작은따옴표를 앞세워 수식 실행을 막는다")
    void neutralizesFormulaInjection() {
        assertThat(CsvResponse.escape("=1+1")).isEqualTo("\"'=1+1\"");
        assertThat(CsvResponse.escape("+82101234")).isEqualTo("\"'+82101234\"");
        assertThat(CsvResponse.escape("-5")).isEqualTo("\"'-5\"");
        assertThat(CsvResponse.escape("@cmd")).isEqualTo("\"'@cmd\"");
    }

    @Test
    @DisplayName("null 과 빈 문자열은 빈 셀이 된다 — 수식 판정도 하지 않는다")
    void nullBecomesEmptyCell() {
        assertThat(CsvResponse.escape(null)).isEqualTo("\"\"");
        assertThat(CsvResponse.escape("")).isEqualTo("\"\"");
    }

    @Test
    @DisplayName("헤더만 있고 데이터가 없어도 유효한 CSV 다")
    void headerOnlyIsValid() {
        String body = bodyOf(export(List.of()));

        assertThat(body).isEqualTo("﻿\"이름\",\"메모\"\r\n");
    }

    @Test
    @DisplayName("파일명은 접두사와 오늘 날짜로 만들어지고 첨부로 내려간다")
    void attachesFileNameWithDate() {
        ResponseEntity<ByteArrayResource> response = export(List.of());

        String disposition = response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).startsWith("attachment;");
        assertThat(disposition).contains("members_" + LocalDate.now() + ".csv");
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualToIgnoringCase("text/csv;charset=UTF-8");
    }

    /**
     * 잘렸는지 알리는 헤더 — 여섯 컨트롤러에 복사돼 있던 두 줄을 여기로 옮겼다.
     *
     * <p>지키려는 것은 "붙이는 방식"이 아니라 <b>빠뜨릴 수 없다</b>는 성질이다. 헤더가 없는 CSV 는
     * 깨지지 않는다 — 열리고, 행도 들어 있고, 잘렸다는 말만 없다. 받은 사람은 5,000 행짜리
     * 파일을 전량으로 믿고 정산에 쓴다.
     */
    @Test
    @DisplayName("전체 건수를 아는 export 는 건수와 잘림 여부를 함께 내보낸다")
    void emitsTotalAndTruncated() {
        ResponseEntity<ByteArrayResource> response =
                export(List.of(row("가"), row("나")), ExportScope.of(4207, true));

        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst("X-Export-Total")).isEqualTo("4207");
        assertThat(headers.getFirst("X-Export-Truncated")).isEqualTo("true");
        assertThat(headers.getFirst("X-Export-Limit")).isNull();
    }

    /**
     * 랭킹처럼 <b>전체 건수를 모르는</b> export. 담긴 행 수를 전체인 척 내보내면 "20개 중 20개"라는
     * 거짓 헤더가 나가므로, 전체 건수는 아예 말하지 않고 상한만 밝힌다.
     */
    @Test
    @DisplayName("상한까지 담은 export 는 전체 건수를 말하지 않고 상한만 밝힌다")
    void limitedOmitsTotal() {
        ResponseEntity<ByteArrayResource> response =
                export(List.of(row("가"), row("나")), ExportScope.limited(2));

        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getFirst("X-Export-Limit")).isEqualTo("2");
        assertThat(headers.getFirst("X-Export-Total")).isNull();
    }

    @Test
    @DisplayName("상한에 닿았으면 잘렸다고 본다 — 정확히 상한과 같은 경우는 구분할 수 없다")
    void limitedTruncationFromRowCount() {
        assertThat(export(List.of(row("가"), row("나")), ExportScope.limited(2))
                .getHeaders().getFirst("X-Export-Truncated")).isEqualTo("true");

        assertThat(export(List.of(row("가")), ExportScope.limited(2))
                .getHeaders().getFirst("X-Export-Truncated")).isEqualTo("false");
    }

    @Test
    @DisplayName("부가 헤더는 X-Export- 로 시작해야 하고 예약 헤더는 덮어쓸 수 없다")
    void extraHeadersAreConstrained() {
        ResponseEntity<ByteArrayResource> response = export(
                List.of(row("가")),
                ExportScope.of(1, false).with("X-Export-Range", "2026-08-01~2026-08-26"));
        assertThat(response.getHeaders().getFirst("X-Export-Range"))
                .isEqualTo("2026-08-01~2026-08-26");

        ExportScope scope = ExportScope.of(1, false);
        assertThatThrownBy(() -> scope.with("X-Total", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        // 덮어쓰기를 허용하면 이 클래스를 우회해 "안 잘렸다"고 말할 수 있게 된다.
        assertThatThrownBy(() -> scope.with("X-Export-Truncated", "false"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("범위를 밝히지 않으면 CSV 자체가 만들어지지 않는다")
    void scopeIsRequired() {
        assertThatThrownBy(() -> CsvResponse.of("members", List.of("이름"), List.of(row("가")),
                r -> List.of(r.name()), null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
