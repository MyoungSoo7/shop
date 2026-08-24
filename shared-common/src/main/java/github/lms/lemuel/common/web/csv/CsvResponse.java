package github.lms.lemuel.common.web.csv;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Function;

/**
 * 관리자 목록을 CSV 로 내려주는 공용 헬퍼.
 *
 * <p>운영자는 화면에서 본 목록을 결국 엑셀로 가져가 정산 담당자·감사인에게 보낸다. 그 경로가
 * 없으면 화면을 스크린샷으로 찍거나 DB 를 직접 열게 되는데, 둘 다 감사 관점에서 나쁜 습관이다.
 *
 * <p><b>UTF-8 BOM 을 붙이는 이유</b>: 붙이지 않으면 Excel 이 CSV 를 시스템 기본 인코딩(한국어
 * Windows 는 CP949)으로 읽어 한글이 전부 깨진다. "인코딩을 지정해 열라"는 안내는 현실에서
 * 지켜지지 않는다 — 파일이 스스로 밝히게 하는 편이 맞다.
 *
 * <p><b>수식 주입(CSV injection) 차단</b>: {@code =}, {@code +}, {@code -}, {@code @} 로 시작하는
 * 값은 Excel 이 <b>수식</b>으로 해석한다. 사용자가 입력한 이름·메모가 그대로 들어가는 목록이라
 * 앞에 작은따옴표를 붙여 문자열로 못박는다. 감사 로그를 열었을 뿐인데 셀이 실행되는 일은
 * 없어야 한다.
 */
public final class CsvResponse {

    /** Excel 이 인코딩을 스스로 알아보게 하는 바이트열. */
    private static final String UTF8_BOM = "﻿";

    private CsvResponse() {
    }

    /**
     * 행 목록을 CSV 첨부 응답으로 만든다.
     *
     * @param baseName 파일명 접두사 — 실제 파일명은 {@code {baseName}_{오늘}.csv}
     * @param headers  헤더 행
     * @param rows     데이터 원본
     * @param mapper   한 행을 셀 문자열 목록으로 바꾸는 함수(길이는 headers 와 같아야 한다)
     */
    public static <T> ResponseEntity<ByteArrayResource> of(String baseName,
                                                           List<String> headers,
                                                           List<T> rows,
                                                           Function<T, List<String>> mapper) {
        StringBuilder body = new StringBuilder(UTF8_BOM);
        appendRow(body, headers);
        for (T row : rows) {
            appendRow(body, mapper.apply(row));
        }

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        String fileName = baseName + "_" + LocalDate.now() + ".csv";

        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }

    private static void appendRow(StringBuilder body, List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                body.append(',');
            }
            body.append(escape(cells.get(i)));
        }
        body.append("\r\n");
    }

    /**
     * 한 셀을 CSV 규격(RFC 4180)으로 감싼다.
     *
     * <p>따옴표·쉼표·줄바꿈이 없어도 항상 감싸는 이유: 조건부로 감싸면 "지금은 쉼표가 없어서
     * 통과하는" 값이 데이터에 따라 어느 날 깨진다. 항상 감싸면 그 분기 자체가 없다.
     */
    static String escape(String value) {
        String safe = value == null ? "" : neutralizeFormula(value);
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String neutralizeFormula(String value) {
        if (value.isEmpty()) {
            return value;
        }
        char head = value.charAt(0);
        if (head == '=' || head == '+' || head == '-' || head == '@') {
            return "'" + value;
        }
        return value;
    }
}
