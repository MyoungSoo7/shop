package github.lms.lemuel.shipping.application.port.out;

import github.lms.lemuel.shipping.domain.ShippingStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 택배사 배송 스캔 조회.
 *
 * <p><b>이 포트가 있는 이유는 키를 서버 안에 가두기 위해서다.</b> 레거시 커머스(ssgb2e)는 조회
 * 화면의 JS 가 숨은 폼에 <i>택배사 API 키를 직접 채워</i> 택배사 팝업으로 POST 했다. 키가 페이지
 * 소스에 그대로 있으니 로그인하지 않은 누구나 볼 수 있었고, 한 번 새면 회수는 키 교체뿐이다.
 * 여기서는 서버만 키를 알고, 브라우저에는 정규화된 이력만 내려간다.
 *
 * <p><b>실패는 예외가 아니라 값이다.</b> 택배사 장애는 드문 사고가 아니라 정상 범위의 사건이다.
 * 예외로 올리면 호출부가 한 번만 잊어도 배송 조회 전체가 500 이 된다 — 우리가 아는 내부 이력까지
 * 같이 사라진다. 그래서 {@link Result} 로 "못 가져왔다"를 돌려주고, 판단은 호출부가 한다.
 */
public interface CarrierTrackingPort {

    /**
     * 연동이 실제로 동작할 수 있는 상태인가(설정 ON + 자격증명 존재).
     *
     * <p>꺼져 있는 것과 실패한 것은 다르다. 꺼져 있으면 사용자에게 알릴 일이 아니고, 실패했으면
     * 알려야 한다. 호출부가 그 둘을 구분할 수 있도록 조회 전에 물어볼 자리를 둔다.
     */
    boolean enabled();

    /** 조회. 절대 예외를 던지지 않는다 — 모든 실패는 {@link Result#unavailable(String)} 이다. */
    Result fetch(String carrier, String trackingNumber);

    /**
     * 택배사 스캔 한 줄.
     *
     * @param status      우리 상태머신으로 옮긴 값(택배사 단계 구분은 우리 것보다 잘게 나뉜다)
     * @param description 택배사가 준 문구 그대로. 우리가 다시 쓰면 정보가 줄기만 한다
     * @param location    스캔 지점
     * @param occurredAt  택배사가 알려준 발생 시각(조회 시각이 아니다)
     */
    record Scan(ShippingStatus status, String description, String location, LocalDateTime occurredAt) {}

    /**
     * 조회 결과.
     *
     * @param unavailableReason {@code null} 이면 성공. 스캔이 0 건인 성공(아직 집화 전)과
     *                          실패는 서로 다른 사실이므로 목록 크기로 구분하지 않는다
     */
    record Result(List<Scan> scans, String unavailableReason) {

        public Result {
            scans = List.copyOf(scans);
        }

        public static Result of(List<Scan> scans) {
            return new Result(scans, null);
        }

        public static Result unavailable(String reason) {
            return new Result(List.of(), reason);
        }

        public boolean available() {
            return unavailableReason == null;
        }
    }
}
