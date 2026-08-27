package github.lms.lemuel.shipping.adapter.out.carrier;

import github.lms.lemuel.shipping.application.port.out.CarrierTrackingPort;
import github.lms.lemuel.shipping.domain.ShippingStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 택배사 배송 조회 중계 어댑터 — <b>키는 서버 밖으로 나가지 않는다</b>.
 *
 * <h2>레거시가 어떻게 했었나</h2>
 * ssgb2e 의 주문 조회 화면은 배송 추적을 <i>브라우저에서</i> 처리했다. 페이지의 JS 가 숨은 폼에
 * 택배사 API 키를 채워 넣고 택배사 팝업으로 POST 하는 방식이라, 키가 <b>페이지 소스에 평문으로</b>
 * 실려 나갔다. 로그인 없이도 볼 수 있었고, 한 번 새면 회수 방법은 키 교체뿐이다. 서버에도
 * 조회 유틸이 하나 있었지만 호출하는 곳이 없는 죽은 코드였다.
 *
 * <p>그래서 여기서는 <b>서버가 대신 호출</b>하고 정규화한 이력만 응답에 싣는다. 키는 설정에서만
 * 오고, 로그·응답·예외 메시지 어디에도 실리지 않는다.
 *
 * <h2>택배사 코드는 내장하지 않는다</h2>
 * 배송 조회 API 는 택배사를 자체 코드로 식별하는데, 우리 {@code shipments.carrier} 는 표시명
 * ("CJ대한통운")이다. 그 대응표를 코드에 박아 두면 <b>확인할 수 없는 값</b>이 소스에 남고, 중계사가
 * 코드를 바꾸면 배포 없이는 못 고친다. 대신 {@code app.carrier-tracking.carrier-codes} 로 받고,
 * 매핑이 없는 택배사는 조회를 시도하지 않고 그 사실을 사유로 돌려준다.
 *
 * <h2>실패</h2>
 * 모든 실패는 {@link Result#unavailable(String)} 이다. 타임아웃은 짧게 잡는다 — 이 조회는 배송
 * 화면을 그리는 동기 경로에 있고, 여기서 오래 매달리면 우리가 이미 알고 있는 내부 이력조차
 * 늦게 보인다.
 */
public class HttpCarrierTrackingAdapter implements CarrierTrackingPort {

    private static final Logger log = LoggerFactory.getLogger(HttpCarrierTrackingAdapter.class);

    /** 중계사 응답의 필드 이름. 규격이 바뀌면 <b>여기만</b> 고친다. */
    private static final String FIELD_DETAILS = "trackingDetails";
    private static final String FIELD_TIME_EPOCH = "time";
    private static final String FIELD_TIME_TEXT = "timeString";
    private static final String FIELD_KIND = "kind";
    private static final String FIELD_WHERE = "where";
    private static final String FIELD_LEVEL = "level";
    private static final String FIELD_MESSAGE = "msg";

    private static final DateTimeFormatter TIME_TEXT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String GENERIC_FAILURE = "택배사 배송 정보를 불러오지 못했습니다.";

    private final String endpoint;
    private final String apiKey;
    private final Map<String, String> carrierCodes;
    private final RestTemplate restTemplate;

    public HttpCarrierTrackingAdapter(String endpoint, String apiKey, Map<String, String> carrierCodes) {
        this(endpoint, apiKey, carrierCodes, defaultRestTemplate());
    }

    /** 테스트 전용 — {@code MockRestServiceServer} 를 물릴 수 있도록 주입받는다. */
    HttpCarrierTrackingAdapter(String endpoint, String apiKey, Map<String, String> carrierCodes,
                               RestTemplate restTemplate) {
        this.endpoint = endpoint == null ? "" : endpoint.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.carrierCodes = carrierCodes == null ? Map.of() : Map.copyOf(carrierCodes);
        this.restTemplate = restTemplate;
    }

    private static RestTemplate defaultRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(3));
        return new RestTemplate(factory);
    }

    /**
     * 설정이 갖춰졌을 때만 켜진다.
     *
     * <p>키가 비었으면 <b>기동을 실패시키지 않고</b> 꺼진 상태로 둔다. 현금영수증 연동과 다르게
     * 대하는 이유는 실패의 무게가 다르기 때문이다 — 발급되지 않은 영수증은 세무 서류가 비는
     * 사고지만, 택배사 스캔은 이미 성립한 타임라인에 얹는 부가 정보다. 그것 하나 때문에 커머스
     * 전체가 못 뜨는 쪽이 더 나쁘다. 대신 켜 놓고 키를 빠뜨린 상황은 WARN 으로 드러낸다.
     */
    @Override
    public boolean enabled() {
        return !endpoint.isEmpty() && !apiKey.isEmpty();
    }

    @Override
    public Result fetch(String carrier, String trackingNumber) {
        if (!enabled()) {
            return Result.unavailable(DisabledCarrierTrackingAdapter.REASON);
        }
        String code = carrierCodes.get(carrier);
        if (code == null || code.isBlank()) {
            // 코드를 모르면 조회 자체가 성립하지 않는다. 아무 코드나 넣어 보는 것은 남의 운송장을
            // 조회하는 일이 될 수 있으므로 시도하지 않는다.
            return Result.unavailable("배송 조회를 지원하지 않는 택배사입니다: " + carrier);
        }

        String url = UriComponentsBuilder.fromUriString(endpoint)
                .queryParam("t_key", apiKey)
                .queryParam("t_code", code)
                .queryParam("t_invoice", normalize(trackingNumber))
                .toUriString();

        try {
            Map<?, ?> body = restTemplate.getForObject(url, Map.class);
            if (body == null) {
                return Result.unavailable(GENERIC_FAILURE);
            }
            Object details = body.get(FIELD_DETAILS);
            if (!(details instanceof List<?> rows)) {
                // 중계사는 오류도 200 으로 돌려준다. 사유 문구는 우리 것으로 바꾼다 — 중계사 문구를
                // 그대로 내보내면 계정 상태 같은 내부 사정이 고객 화면에 실린다.
                log.warn("택배사 배송 조회 응답에 이력이 없습니다: carrier={}, msg={}",
                        carrier, body.get(FIELD_MESSAGE));
                return Result.unavailable(GENERIC_FAILURE);
            }
            return Result.of(toScans(rows));

        } catch (RuntimeException e) {
            // e.toString() 에는 요청 URL 이 섞여 들어올 수 있고, URL 에는 키가 있다.
            // 그래서 예외 타입만 남긴다.
            log.warn("택배사 배송 조회 실패: carrier={}, cause={}", carrier, e.getClass().getSimpleName());
            return Result.unavailable(GENERIC_FAILURE);
        }
    }

    private static List<Scan> toScans(List<?> rows) {
        List<Scan> scans = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> detail)) {
                continue;
            }
            LocalDateTime occurredAt = readTime(detail);
            String description = text(detail.get(FIELD_KIND));
            if (occurredAt == null || description == null) {
                // 시각이나 설명이 없는 줄은 버린다. 지금 시각을 붙여 채우면 타임라인 순서가
                // 뒤집히고, 사용자는 방금 무슨 일이 일어난 것으로 읽는다.
                continue;
            }
            scans.add(new Scan(toStatus(detail.get(FIELD_LEVEL)), description,
                    text(detail.get(FIELD_WHERE)), occurredAt));
        }
        return scans;
    }

    /** epoch millis 를 우선한다 — 문자열 시각은 형식이 흔들려도 숫자는 흔들리지 않는다. */
    private static LocalDateTime readTime(Map<?, ?> detail) {
        Object epoch = detail.get(FIELD_TIME_EPOCH);
        if (epoch instanceof Number millis) {
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis.longValue()), ZoneId.systemDefault());
        }
        String text = text(detail.get(FIELD_TIME_TEXT));
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, TIME_TEXT_FORMAT);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * 중계사의 단계 번호를 우리 상태로 옮긴다.
     *
     * <p>대응은 <b>거칠다</b> — 중계사 단계는 우리 상태머신보다 잘게 나뉘고, 그 세부는 택배사마다
     * 다르다. 잃어버리는 정보가 없도록 원래 문구({@code kind})를 설명으로 그대로 싣는다. 이 값은
     * 타임라인의 배지 색을 고르는 데만 쓰이고, 우리 배송 상태를 바꾸지는 않는다.
     */
    private static ShippingStatus toStatus(Object level) {
        if (!(level instanceof Number n)) {
            return ShippingStatus.IN_TRANSIT;
        }
        int value = n.intValue();
        if (value >= 6) return ShippingStatus.DELIVERED;
        if (value >= 3) return ShippingStatus.IN_TRANSIT;
        if (value >= 2) return ShippingStatus.SHIPPED;
        return ShippingStatus.READY;
    }

    /** 운송장 번호는 하이픈을 빼고 보낸다(화면 입력에는 섞여 들어온다). */
    private static String normalize(String trackingNumber) {
        return trackingNumber == null ? "" : trackingNumber.replace("-", "").trim();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }
}
