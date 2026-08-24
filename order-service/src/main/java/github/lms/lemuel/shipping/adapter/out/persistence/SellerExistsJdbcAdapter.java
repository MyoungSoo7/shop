package github.lms.lemuel.shipping.adapter.out.persistence;

import github.lms.lemuel.shipping.application.port.out.SellerExistsPort;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 셀러 존재 확인 어댑터.
 *
 * <p>user 도메인의 JPA 리포지토리를 import 하지 않는다(어댑터는 타 도메인 영속 계층을 직접
 * 참조하지 않는다) — {@link ProductShippingChargeJdbcAdapter} 와 같은 읽기 전용 SQL 방식이다.
 *
 * <p><b>스키마 한정 필수</b> — JPA 는 {@code default_schema=opslab} 로 돌지만 JdbcTemplate 은
 * {@code search_path} 를 따른다. 한정하지 않으면 로컬에서만 통하고 배포 후 터진다.
 */
@Repository
public class SellerExistsJdbcAdapter implements SellerExistsPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SellerExistsJdbcAdapter(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean existsById(Long sellerId) {
        if (sellerId == null) {
            return false;
        }
        Boolean found = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM opslab.users u WHERE u.id = :sellerId)",
                new MapSqlParameterSource("sellerId", sellerId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }
}
