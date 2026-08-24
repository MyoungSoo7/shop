package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.LoadPaymentOwnerPort;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * 결제 → 주문 → 주문자 해석 어댑터(소유권 대조 전용).
 *
 * <p>스키마를 {@code opslab.} 으로 한정한다. Hibernate 의 {@code default_schema} 는 JPA 경로에만
 * 걸리고 JdbcTemplate 은 search_path 를 따르므로, 한정하지 않으면 <b>배포 환경에서만</b>
 * "relation does not exist" 로 터진다.
 */
@Repository
public class PaymentOwnerJdbcAdapter implements LoadPaymentOwnerPort {

    private final JdbcTemplate jdbcTemplate;

    public PaymentOwnerJdbcAdapter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<Long> findOwnerUserId(Long paymentId) {
        String sql = """
                SELECT o.user_id
                FROM opslab.payments pay
                JOIN opslab.orders o ON o.id = pay.order_id
                WHERE pay.id = ?
                """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, Long.class, paymentId));
        } catch (EmptyResultDataAccessException e) {
            // 결제나 주문이 없다 — 소유자를 모르므로 빈 값. 호출측이 fail-closed 로 막는다.
            return Optional.empty();
        }
    }
}
