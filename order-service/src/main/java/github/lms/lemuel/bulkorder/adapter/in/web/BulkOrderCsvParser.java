package github.lms.lemuel.bulkorder.adapter.in.web;

import github.lms.lemuel.bulkorder.domain.exception.InvalidBulkOrderFileException;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 대량주문 CSV 파서 — <b>파일 형식만</b> 책임진다. 값의 옳고 그름은 도메인(열 스펙)이 본다.
 *
 * <p>헤더를 <b>이름으로 읽지 않는다</b>(송장 업로드와 다른 점). 대량주문 양식은 열 정의가 DB 에
 * 있고 그 정의의 {@code columnIndex} 가 곧 열 위치이므로, 파일은 위치로 읽어야 정의와 맞물린다.
 * 첫 줄은 사람이 읽는 헤더로 보고 건너뛴다.
 *
 * <p>POI(엑셀) 대신 CSV 인 이유: 의존성 추가 0 이고 저장소에 이미 선례가 있다
 * ({@code TrackingNumberCsvParser}). 레거시가 xlsx 를 읽던 것은 그쪽 운영자 환경 때문이지
 * 도메인 요구가 아니다.
 */
@Component
public class BulkOrderCsvParser {

    /** 한 번에 받을 수 있는 최대 행 수 — 무제한이면 파일 하나가 서버를 멈춘다. */
    private static final int MAX_ROWS = 5_000;

    /** @return 데이터 행 목록(헤더 제외). 각 행은 셀 문자열 목록 */
    public List<List<String>> parse(InputStream in) {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) {
                throw new InvalidBulkOrderFileException("빈 파일입니다");
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue; // 엑셀이 흔히 남기는 꼬리 빈 줄 — 오류로 세지 않는다
                }
                if (rows.size() >= MAX_ROWS) {
                    throw new InvalidBulkOrderFileException(
                            "한 번에 올릴 수 있는 행은 " + MAX_ROWS + "건까지입니다. 파일을 나눠 올려 주세요.");
                }
                rows.add(Arrays.stream(line.split(",", -1)).map(BulkOrderCsvParser::clean).toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (rows.isEmpty()) {
            throw new InvalidBulkOrderFileException("데이터 행이 없습니다. 헤더만 있는 파일인지 확인해 주세요.");
        }
        return rows;
    }

    /** 엑셀이 UTF-8 저장 시 붙이는 BOM 과 값을 감싼 따옴표를 걷어낸다. */
    private static String clean(String cell) {
        String value = cell.startsWith("﻿") ? cell.substring(1) : cell;
        value = value.trim();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }
}
