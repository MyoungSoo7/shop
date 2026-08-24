package github.lms.lemuel.shipping.adapter.in.web;

import github.lms.lemuel.shipping.domain.TrackingNumberRegistration;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 송장 일괄 등록 CSV 파서 — <b>파일 형식만</b> 책임진다(행 유효성은 도메인).
 *
 * <pre>
 * order_id,carrier,tracking_number
 * 7,CJ,1234567890
 * </pre>
 *
 * <p>헤더는 <b>이름으로</b> 읽는다 — 운영자가 스프레드시트에서 열 순서를 바꿔도 깨지지 않게.
 *
 * <p><b>실패의 층을 나눈다</b>: 헤더가 없으면 행별로 알릴 방법이 없으므로 파일 전체를 거절하고,
 * 개별 행의 문제(숫자 아닌 주문번호·열 부족)는 그 행만 사유와 함께 거절해 나머지를 살린다.
 * 한 행 때문에 수백 행짜리 파일을 다시 만들게 하지 않기 위함이다.
 *
 * <p>POI(엑셀) 대신 CSV 인 이유: 의존성 추가 0 이고, 저장소에 이미 CSV 파서 선례가 있다
 * (settlement 의 {@code CsvPgFileParserAdapter}).
 */
@Component
public class TrackingNumberCsvParser {

    private static final String ORDER_ID = "order_id";
    private static final String CARRIER = "carrier";
    private static final String TRACKING_NUMBER = "tracking_number";
    private static final List<String> REQUIRED = List.of(ORDER_ID, CARRIER, TRACKING_NUMBER);

    public List<TrackingNumberRegistration> parse(InputStream in) {
        List<TrackingNumberRegistration> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            Map<String, Integer> header = readHeader(reader);
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                rows.add(toRow(line.split(",", -1), header));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return TrackingNumberRegistration.rejectDuplicates(rows);
    }

    private Map<String, Integer> readHeader(BufferedReader reader) throws IOException {
        String first = reader.readLine();
        if (first == null) {
            throw new InvalidTrackingCsvException("빈 파일입니다");
        }
        // 엑셀이 UTF-8 저장 시 흔히 붙이는 BOM — 붙었다고 첫 헤더를 못 읽으면 안 된다.
        String[] columns = stripBom(first).split(",", -1);

        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < columns.length; i++) {
            index.put(columns[i].trim().toLowerCase(), i);
        }
        List<String> missing = REQUIRED.stream().filter(c -> !index.containsKey(c)).toList();
        if (!missing.isEmpty()) {
            throw new InvalidTrackingCsvException("필수 헤더 누락: " + String.join(", ", missing));
        }
        return index;
    }

    private TrackingNumberRegistration toRow(String[] cells, Map<String, Integer> header) {
        String rawOrderId = cell(cells, header.get(ORDER_ID));
        String carrier = cell(cells, header.get(CARRIER));
        String tracking = cell(cells, header.get(TRACKING_NUMBER));

        Long orderId = parseOrderId(rawOrderId);
        if (orderId == null) {
            // 도메인은 "주문번호 없음"만 알면 되지만, 운영자에게는 원래 값이 보여야 고칠 수 있다.
            return new TrackingNumberRegistration(null, carrier, tracking, false,
                    "주문번호를 숫자로 읽을 수 없습니다: '" + rawOrderId + "'");
        }
        return TrackingNumberRegistration.of(orderId, carrier, tracking);
    }

    /** 열이 모자란 행도 파일을 깨지 않는다 — 그 행만 사유와 함께 거절된다. */
    private static String cell(String[] cells, int index) {
        return index < cells.length ? cells[index] : null;
    }

    private static Long parseOrderId(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripBom(String line) {
        return line.startsWith("﻿") ? line.substring(1) : line;
    }
}
