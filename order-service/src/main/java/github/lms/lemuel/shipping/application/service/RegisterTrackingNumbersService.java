package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.RegisterTrackingNumbersUseCase;
import github.lms.lemuel.shipping.application.port.in.ShippingUseCase;
import github.lms.lemuel.shipping.domain.TrackingNumberRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 송장 일괄 등록.
 *
 * <p><b>트랜잭션을 걸지 않는다(의도)</b> — 한 행의 실패로 이미 반영된 수백 행을 되돌리면, 운영자는
 * 어디까지 됐는지 모른 채 파일 전체를 다시 올려야 한다. 출고는 행마다 독립적인 사실이므로 건별로
 * 확정하고, 실패한 행만 사유와 함께 돌려줘 그 행만 고쳐 재업로드하게 한다(재실행해도 이미 출고된
 * 행은 도메인 전이 가드가 막는다).
 *
 * <p>유효성 판정은 도메인({@link TrackingNumberRegistration})이 이미 끝냈다 — 여기서는 그 결과를
 * 존중해 유효한 행만 출고를 시도한다. 미리보기와 실행이 같은 판정을 보므로 "미리보기엔 통과였는데
 * 실행에서 빠졌다"가 생기지 않는다.
 */
@Service
public class RegisterTrackingNumbersService implements RegisterTrackingNumbersUseCase {

    private static final Logger log = LoggerFactory.getLogger(RegisterTrackingNumbersService.class);

    private final ShippingUseCase shippingUseCase;

    public RegisterTrackingNumbersService(ShippingUseCase shippingUseCase) {
        this.shippingUseCase = shippingUseCase;
    }

    @Override
    public BulkTrackingResult register(List<TrackingNumberRegistration> rows, boolean dryRun) {
        List<ResultLine> lines = new ArrayList<>(rows.size());
        int applied = 0;
        int failed = 0;

        for (TrackingNumberRegistration row : rows) {
            if (!row.valid()) {
                lines.add(line(row, false, row.reason()));
                failed++;
                continue;
            }
            if (dryRun) {
                applied++;
                lines.add(line(row, true, null));
                continue;
            }
            try {
                shippingUseCase.ship(row.orderId(), row.carrier(), row.trackingNumber());
                applied++;
                lines.add(line(row, true, null));
            } catch (RuntimeException e) {
                // 배송 미생성·이미 출고 등 — 이 행만 실패로 남기고 나머지는 계속 처리한다.
                failed++;
                lines.add(line(row, false, e.getMessage()));
                log.warn("송장 등록 실패: orderId={}, 사유={}", row.orderId(), e.toString());
            }
        }
        if (!rows.isEmpty()) {
            log.info("송장 일괄 등록{}: 적용={}, 실패={}", dryRun ? "(dryRun)" : "", applied, failed);
        }
        return new BulkTrackingResult(applied, failed, dryRun, List.copyOf(lines));
    }

    private static ResultLine line(TrackingNumberRegistration row, boolean applied, String reason) {
        return new ResultLine(row.orderId(), row.carrier(), row.trackingNumber(), applied, reason);
    }
}
