package github.lms.lemuel.shipping.application.service;

import github.lms.lemuel.shipping.application.port.in.SafetyNumberUseCase;
import github.lms.lemuel.shipping.application.port.out.SafetyNumberPort;
import github.lms.lemuel.shipping.domain.SafetyNumber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 안심번호 배정·회수 서비스.
 *
 * <p>풀이 유한하다는 점이 설계를 지배한다:
 * <ul>
 *   <li><b>배정은 멱등</b> — 같은 주문에 두 번 요청해도 같은 번호를 돌려준다. 아니면 재시도마다
 *       번호가 한 개씩 사라져 풀이 마른다.</li>
 *   <li><b>고갈은 실패가 아니다</b> — 번호가 없다고 배송 생성을 막으면 주문 흐름 전체가 멈춘다.
 *       비어 있는 결과 + WARN 으로 착지하고, 그 경우 실번호가 노출된다는 사실을 운영이 알게 한다.</li>
 *   <li><b>회수는 만료 기준</b> — 배송이 끝났다는 신호 대신 유효기간을 쓴다. 배송 완료 이벤트를
 *       놓치면 번호가 영구히 묶이지만, 시간은 반드시 흐른다.</li>
 * </ul>
 */
@Service
public class SafetyNumberService implements SafetyNumberUseCase {

    private static final Logger log = LoggerFactory.getLogger(SafetyNumberService.class);

    private final SafetyNumberPort port;
    private final int validityDays;

    public SafetyNumberService(SafetyNumberPort port,
                               @Value("${app.shipping.safety-number.validity-days:7}") int validityDays) {
        this.port = port;
        this.validityDays = validityDays;
    }

    @Override
    @Transactional
    public Optional<SafetyNumber> assignForOrder(Long orderId) {
        Optional<SafetyNumber> existing = port.findAssignedByOrderId(orderId);
        if (existing.isPresent()) {
            return existing;   // 멱등 — 재시도가 풀을 갉아먹지 않는다
        }

        Optional<SafetyNumber> claimed = port.claimAvailable();
        if (claimed.isEmpty()) {
            log.warn("안심번호 풀 고갈 — 실번호가 그대로 노출된다: orderId={}", orderId);
            return Optional.empty();
        }

        SafetyNumber number = claimed.get();
        number.assignTo(orderId, OffsetDateTime.now(), validityDays);
        SafetyNumber saved = port.save(number);
        log.info("안심번호 배정: orderId={}, number={}, expiresAt={}",
                orderId, saved.getVirtualNumber(), saved.getExpiresAt());
        return Optional.of(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SafetyNumber> findForOrder(Long orderId) {
        return port.findAssignedByOrderId(orderId);
    }

    @Override
    @Transactional
    public int releaseExpired(OffsetDateTime now, int limit) {
        List<SafetyNumber> expired = port.findExpired(now, limit);
        for (SafetyNumber number : expired) {
            number.release();
            port.save(number);
        }
        if (!expired.isEmpty()) {
            log.info("안심번호 회수: {} 건", expired.size());
        }
        return expired.size();
    }
}
