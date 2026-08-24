package github.lms.lemuel.shipping.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 송장 일괄 등록 1행 — 통과 여부와 사유를 함께 담는다.
 *
 * <p>업로드는 수백 행이 한꺼번에 들어온다. 한 행이 잘못됐다고 파일 전체를 거절하면 운영자는 무엇이
 * 틀렸는지 모른 채 파일만 다시 만들게 되므로, <b>행마다</b> 통과/사유를 남겨 미리보기에 그대로 보여준다.
 *
 * <p>판정을 도메인이 소유하는 이유: 미리보기와 실행이 같은 규칙을 봐야 "미리보기에선 통과였는데
 * 실행에서 빠졌다"가 생기지 않는다.
 */
public record TrackingNumberRegistration(Long orderId, String carrier, String trackingNumber,
                                         boolean valid, String reason) {

    public static TrackingNumberRegistration of(Long orderId, String carrier, String trackingNumber) {
        // 엑셀·스프레드시트에서 복사하면 앞뒤 공백이 흔히 딸려 온다 — 그 자체를 오류로 보지 않는다.
        String trimmedCarrier = trim(carrier);
        String trimmedTracking = trim(trackingNumber);

        String reason = validate(orderId, trimmedCarrier, trimmedTracking);
        return new TrackingNumberRegistration(orderId, trimmedCarrier, trimmedTracking,
                reason == null, reason);
    }

    private static String validate(Long orderId, String carrier, String trackingNumber) {
        if (orderId == null) {
            return "주문번호가 없어 어느 배송인지 특정할 수 없습니다";
        }
        if (carrier == null || carrier.isEmpty()) {
            return "택배사가 비어 있습니다";
        }
        if (trackingNumber == null || trackingNumber.isEmpty()) {
            return "운송장번호가 비어 있습니다";
        }
        return null;
    }

    /**
     * 같은 주문을 두 번 이상 지정한 행을 거절한다 — 어느 운송장이 맞는지 파일만으로는 알 수 없고,
     * 나중 값으로 덮으면 앞 행을 조용히 버리는 셈이 된다.
     *
     * <p>이미 거절된 행은 주문을 선점하지 않는다: 무효한 행 때문에 뒤의 정상 행까지 중복으로
     * 몰리면 운영자가 고칠 방법이 없다.
     */
    public static List<TrackingNumberRegistration> rejectDuplicates(List<TrackingNumberRegistration> rows) {
        Set<Long> claimed = new HashSet<>();
        List<TrackingNumberRegistration> result = new ArrayList<>(rows.size());
        for (TrackingNumberRegistration row : rows) {
            if (row.valid() && !claimed.add(row.orderId())) {
                result.add(new TrackingNumberRegistration(row.orderId(), row.carrier(), row.trackingNumber(),
                        false, "같은 주문이 파일 안에서 중복 지정되었습니다"));
            } else {
                result.add(row);
            }
        }
        return List.copyOf(result);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
