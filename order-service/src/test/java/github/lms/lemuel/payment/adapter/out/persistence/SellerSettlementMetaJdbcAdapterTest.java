package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.payment.application.port.out.SellerSettlementMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SellerSettlementMetaJdbcAdapterTest {

    @Mock JdbcTemplate jdbcTemplate;

    private SellerSettlementMetaJdbcAdapter adapter() {
        return new SellerSettlementMetaJdbcAdapter(jdbcTemplate);
    }

    private ResultSet rowOf(Long sellerId, String tier, String cycle) throws SQLException {
        ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
        when(rs.getLong("seller_id")).thenReturn(sellerId != null ? sellerId : 0L);
        when(rs.wasNull()).thenReturn(sellerId == null);
        when(rs.getString("seller_tier")).thenReturn(tier);
        when(rs.getString("settlement_cycle")).thenReturn(cycle);
        return rs;
    }

    @SuppressWarnings("unchecked")
    private void givenRows(Long paymentId, ResultSet... rows) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(paymentId)))
                .thenAnswer(inv -> {
                    RowMapper<SellerSettlementMeta> mapper = inv.getArgument(1);
                    java.util.List<SellerSettlementMeta> mapped = new java.util.ArrayList<>();
                    for (int i = 0; i < rows.length; i++) {
                        mapped.add(mapper.mapRow(rows[i], i));
                    }
                    return mapped;
                });
    }

    @Test
    @DisplayName("findByPaymentId: 단일 셀러면 채워진 메타를 반환")
    void findByPaymentId_withSeller_returnsMeta() throws SQLException {
        givenRows(1L, rowOf(42L, "VIP", "T_PLUS_3"));

        Optional<SellerSettlementMeta> result = adapter().findByPaymentId(1L);

        assertThat(result).contains(new SellerSettlementMeta(42L, "VIP", "T_PLUS_3"));
    }

    @Test
    @DisplayName("findByPaymentId: seller_id 컬럼이 NULL 이면 sellerId=null (LEFT JOIN 미할당)")
    void findByPaymentId_nullSeller_returnsNullSellerId() throws SQLException {
        givenRows(2L, rowOf(null, null, null));

        Optional<SellerSettlementMeta> result = adapter().findByPaymentId(2L);

        assertThat(result).isPresent();
        assertThat(result.get().sellerId()).isNull();
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("findByPaymentId: payment/order/product 체인이 없으면 empty")
    void findByPaymentId_missingChain_returnsEmpty() {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(999L)))
                .thenReturn(List.of());

        Optional<SellerSettlementMeta> result = adapter().findByPaymentId(999L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByPaymentId: 라인 셀러가 여럿이면 단일 귀속 불가 — empty (발행 생략 유도)")
    void findByPaymentId_multipleSellers_returnsEmpty() throws SQLException {
        givenRows(3L, rowOf(42L, "VIP", "T_PLUS_3"), rowOf(43L, "NORMAL", "T_PLUS_7"));

        Optional<SellerSettlementMeta> result = adapter().findByPaymentId(3L);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByPaymentId: 배정 셀러와 미배정(NULL) 라인이 섞여도 단일 귀속 불가 — empty")
    void findByPaymentId_mixedAssignedAndUnassigned_returnsEmpty() throws SQLException {
        givenRows(4L, rowOf(42L, "VIP", "T_PLUS_3"), rowOf(null, null, null));

        Optional<SellerSettlementMeta> result = adapter().findByPaymentId(4L);

        assertThat(result).isEmpty();
    }
}
