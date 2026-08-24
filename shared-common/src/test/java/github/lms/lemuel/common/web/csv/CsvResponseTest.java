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
        return CsvResponse.of("members", List.of("이름", "메모"), rows,
                r -> List.of(r.name(), r.memo()));
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
}
