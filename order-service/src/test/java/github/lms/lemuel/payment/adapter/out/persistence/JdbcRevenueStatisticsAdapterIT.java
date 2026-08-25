package github.lms.lemuel.payment.adapter.out.persistence;

import github.lms.lemuel.common.outbox.adapter.out.persistence.OutboxSchema;
import github.lms.lemuel.payment.application.port.in.ViewRevenueStatisticsUseCase.TenderRevenue;
import github.lms.lemuel.payment.application.port.out.LoadRevenueStatisticsPort.DailyAmount;
import github.lms.lemuel.payment.domain.TenderType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 매출 집계 SQL 의 의미 검증 — 실 PostgreSQL.
 *
 * <p>여기서 지키는 것은 <b>시간축</b>이다. 매출은 주문의 현재 상태가 아니라 실제로 돈이 잡힌
 * 시각({@code payments.captured_at})에 달리고, 차감은 환불이 완료된 시각
 * ({@code refunds.completed_at})에 달린다. 두 축이 서로 다르므로 8월에 팔려 9월에 환불된 건은
 * <b>8월 매출로 남고 9월에 차감된다</b>. 이 규칙은 SQL 의 WHERE 절에 들어 있어 가짜 포트로는
 * 증명할 수 없다 — 축을 {@code updated_at} 으로 바꿔 놓아도 단위 테스트는 전부 초록이다.
 *
 * <p>연도를 2099 로 잡는 것은 {@code V17__seed_data} 가 심어 둔 결제와 섞이지 않기 위해서다.
 * 시드와 겹치면 합계가 늘 조금씩 커서 "왜 안 맞지"로 시간을 쓰게 된다.
 */
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
@DataJpaTest
@ImportAutoConfiguration({FlywayAutoConfiguration.class, JdbcTemplateAutoConfiguration.class})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({OutboxSchema.class, JdbcRevenueStatisticsAdapter.class})
@ActiveProfiles("test")
class JdbcRevenueStatisticsAdapterIT {

    static boolean isDockerAvailable() {
        try { DockerClientFactory.instance().client(); return true; }
        catch (Throwable ex) { return false; }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("inter")
            .withUsername("lemuel")
            .withPassword("lemuel");

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    private static final LocalDate AUG_1 = LocalDate.of(2099, 8, 1);
    private static final LocalDate AUG_31 = LocalDate.of(2099, 8, 31);
    private static final LocalDate SEP_30 = LocalDate.of(2099, 9, 30);

    @Autowired JdbcRevenueStatisticsAdapter adapter;
    @Autowired JdbcTemplate jdbc;

    // ── 시간축 ────────────────────────────────────────────────────────────

    /**
     * 이 테스트가 이 클래스의 이유다.
     *
     * <p>8월에 팔려 9월에 환불된 건은 8월 매출에 남고 9월에 차감된다. 판매일로 소급하면 이미
     * 보고된 8월 숫자가 오늘 조용히 바뀌고, 반대로 환불일로 매출까지 옮기면 8월에 있었던 판매가
     * 사라진다. 두 계열의 축이 서로 다르다는 것이 요점이다.
     */
    @Test
    @DisplayName("8월에 팔려 9월에 환불된 건은 8월 매출로 남고 9월에 차감된다")
    void 축이_다른_두_계열() {
        long payment = capturedPayment("30000", at(2099, 8, 10, 14, 0));
        completedRefund(payment, "30000", at(2099, 9, 5, 9, 0));

        assertThat(capturesIn(AUG_1, AUG_31))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.date()).isEqualTo(LocalDate.of(2099, 8, 10));
                    assertThat(d.amount()).isEqualByComparingTo("30000");
                });
        assertThat(refundsIn(AUG_1, AUG_31)).isEmpty();

        assertThat(capturesIn(LocalDate.of(2099, 9, 1), SEP_30)).isEmpty();
        assertThat(refundsIn(LocalDate.of(2099, 9, 1), SEP_30))
                .singleElement()
                .satisfies(d -> assertThat(d.date()).isEqualTo(LocalDate.of(2099, 9, 5)));
    }

    /**
     * 환불된 결제도 <b>수납은 그날 일어난 사실</b>이라 총 수납액에 남는다. 여기서 REFUNDED 를
     * 빼면 환불이 두 번 반영된다 — 매출에서 빠지고 환불액으로도 빠진다.
     */
    @Test
    @DisplayName("REFUNDED 결제의 수납도 그날 매출에 남는다")
    void 환불된_결제도_수납은_남는다() {
        long payment = capturedPayment("20000", at(2099, 8, 3, 10, 0));
        jdbc.update("UPDATE opslab.payments SET status = 'REFUNDED' WHERE id = ?", payment);

        assertThat(capturesIn(AUG_1, AUG_31))
                .singleElement()
                .satisfies(d -> assertThat(d.amount()).isEqualByComparingTo("20000"));
    }

    /**
     * 승인만 되고 매입되지 않은 결제, 실패한 결제, 미입금 만료(가상계좌·무통장)는 돈이 잡히지
     * 않았다. 이것들이 매출에 들어가면 <b>들어오지도 않은 돈</b>이 보고된다.
     */
    @Test
    @DisplayName("AUTHORIZED·FAILED·EXPIRED 결제는 매출이 아니다")
    void 미확정_결제는_제외() {
        payment("11000", at(2099, 8, 4, 10, 0), "AUTHORIZED");
        payment("12000", at(2099, 8, 4, 11, 0), "FAILED");
        payment("13000", at(2099, 8, 4, 12, 0), "EXPIRED");
        payment("14000", at(2099, 8, 4, 13, 0), "CANCELED");

        assertThat(capturesIn(AUG_1, AUG_31)).isEmpty();
    }

    /**
     * 신청만 하고 아직 나가지 않은 환불, 나가지 못한 채 끝난 환불까지 차감하면 <b>있지도 않은
     * 환불</b>이 매출을 깎는다. 실패 환불은 재시도로 계속 갱신되므로 특히 위험하다.
     */
    @Test
    @DisplayName("REQUESTED·FAILED 환불은 차감하지 않는다")
    void 미완료_환불은_제외() {
        long payment = capturedPayment("50000", at(2099, 8, 2, 10, 0));
        refund(payment, "10000", at(2099, 8, 6, 10, 0), "REQUESTED", "k-req");
        refund(payment, "10000", at(2099, 8, 7, 10, 0), "FAILED", "k-fail");

        assertThat(refundsIn(AUG_1, AUG_31)).isEmpty();
    }

    // ── 기간 경계 ─────────────────────────────────────────────────────────

    /**
     * 종료일은 포함이다. 서비스가 상한을 다음 날 0시로 옮기므로 마지막 날 23:59:59 는 들어오고
     * 다음 날 00:00:00 은 빠져야 한다. 이 경계가 어긋나면 월말 하루가 통째로 사라지는데,
     * 비어 보일 뿐 오류를 내지 않는다.
     */
    @Test
    @DisplayName("마지막 날 23:59:59 는 포함, 다음 날 00:00:00 은 제외")
    void 반개구간_경계() {
        capturedPayment("1000", at(2099, 8, 31, 23, 59, 59));
        capturedPayment("9999", at(2099, 9, 1, 0, 0, 0));

        List<DailyAmount> rows = capturesIn(AUG_1, AUG_31);

        assertThat(rows).singleElement().satisfies(d -> {
            assertThat(d.date()).isEqualTo(AUG_31);
            assertThat(d.amount()).isEqualByComparingTo("1000");
        });
    }

    @Test
    @DisplayName("같은 날 여러 건은 한 줄로 집계된다")
    void 같은_날_집계() {
        capturedPayment("1000", at(2099, 8, 9, 1, 0));
        capturedPayment("2000", at(2099, 8, 9, 20, 0));

        assertThat(capturesIn(AUG_1, AUG_31)).singleElement().satisfies(d -> {
            assertThat(d.count()).isEqualTo(2L);
            assertThat(d.amount()).isEqualByComparingTo("3000");
        });
    }

    // ── 결제수단 구성 ──────────────────────────────────────────────────────

    /**
     * 분할결제는 결제 한 건이 여러 수단에 걸친다. {@code payments.payment_method} 한 칸으로 세면
     * 5만원 결제가 포인트 5천 + 카드 4만5천이어도 통째로 한 수단에 붙는다.
     */
    @Test
    @DisplayName("분할결제는 수단별로 갈라 집계된다")
    void 분할결제_구성() {
        long payment = capturedPayment("50000", at(2099, 8, 15, 10, 0));
        tender(payment, 1, TenderType.CARD, "45000", "CAPTURED");
        tender(payment, 2, TenderType.POINT, "5000", "CAPTURED");

        List<TenderRevenue> byTender = adapter.capturedByTender(
                AUG_1.atStartOfDay(), AUG_31.plusDays(1).atStartOfDay());

        assertThat(byTender).extracting(TenderRevenue::tenderType)
                .containsExactly(TenderType.CARD, TenderType.POINT);   // 금액 내림차순
        assertThat(byTender).filteredOn(t -> t.tenderType() == TenderType.POINT)
                .singleElement()
                .satisfies(t -> {
                    assertThat(t.amount()).isEqualByComparingTo("5000");
                    assertThat(t.usesExternalPg())
                            .as("포인트는 내부 잔액 차감이라 새로 들어온 현금이 아니다")
                            .isFalse();
                });
    }

    /**
     * 결제가 캡처됐다는 것이 모든 라인이 캡처됐다는 뜻은 아니다 — 한 라인이 실패해도 다른 라인으로
     * 결제가 성립할 수 있다. 실패 라인을 세면 받지 않은 돈이 수단 구성에 들어간다.
     */
    @Test
    @DisplayName("실패한 tender 라인은 세지 않는다")
    void 실패한_라인은_제외() {
        long payment = capturedPayment("45000", at(2099, 8, 16, 10, 0));
        tender(payment, 1, TenderType.CARD, "45000", "CAPTURED");
        tender(payment, 2, TenderType.KAKAO_PAY, "10000", "FAILED");

        assertThat(adapter.capturedByTender(AUG_1.atStartOfDay(), AUG_31.plusDays(1).atStartOfDay()))
                .extracting(TenderRevenue::tenderType)
                .containsExactly(TenderType.CARD);
    }

    /**
     * 기간 판정은 tender 가 아니라 <b>결제</b>의 {@code captured_at} 으로 한다. tender 행에는 수납
     * 시각이 없고 {@code updated_at} 은 환불 때도 움직이므로, tender 시각으로 자르면 같은 결제의
     * 카드 라인과 포인트 라인이 서로 다른 달에 설 수 있다.
     */
    @Test
    @DisplayName("기간 밖 결제의 tender 는 잡히지 않는다")
    void 기간_밖_결제의_라인은_제외() {
        long inside = capturedPayment("10000", at(2099, 8, 20, 10, 0));
        long outside = capturedPayment("77000", at(2099, 9, 20, 10, 0));
        tender(inside, 1, TenderType.CARD, "10000", "CAPTURED");
        tender(outside, 1, TenderType.NAVER_PAY, "77000", "CAPTURED");

        assertThat(adapter.capturedByTender(AUG_1.atStartOfDay(), AUG_31.plusDays(1).atStartOfDay()))
                .extracting(TenderRevenue::tenderType)
                .containsExactly(TenderType.CARD);
    }

    /**
     * 분할결제 도입 전 결제에는 tender 행이 아예 없다. 수단 합계가 총 수납액에 못 미치는 이
     * 상태를 서비스가 "수단 미상"으로 드러내므로, 어댑터는 <b>없는 걸 만들어 내지 않는다</b>.
     */
    @Test
    @DisplayName("tender 행이 없는 옛 결제는 수단 구성에 나타나지 않는다")
    void 옛_결제는_수단이_없다() {
        capturedPayment("60000", at(2099, 8, 21, 10, 0));   // tender 없음

        LocalDateTime from = AUG_1.atStartOfDay();
        LocalDateTime toExclusive = AUG_31.plusDays(1).atStartOfDay();

        assertThat(adapter.capturedByTender(from, toExclusive)).isEmpty();
        assertThat(adapter.capturesByDay(from, toExclusive))
                .singleElement()
                .satisfies(d -> assertThat(d.amount()).isEqualByComparingTo("60000"));
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────

    private List<DailyAmount> capturesIn(LocalDate from, LocalDate toInclusive) {
        return adapter.capturesByDay(from.atStartOfDay(), toInclusive.plusDays(1).atStartOfDay());
    }

    private List<DailyAmount> refundsIn(LocalDate from, LocalDate toInclusive) {
        return adapter.refundsByDay(from.atStartOfDay(), toInclusive.plusDays(1).atStartOfDay());
    }

    private static LocalDateTime at(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min);
    }

    private static LocalDateTime at(int y, int m, int d, int h, int min, int s) {
        return LocalDateTime.of(y, m, d, h, min, s);
    }

    private long capturedPayment(String amount, LocalDateTime capturedAt) {
        return payment(amount, capturedAt, "CAPTURED");
    }

    private long payment(String amount, LocalDateTime capturedAt, String status) {
        Long orderId = jdbc.queryForObject(
                "INSERT INTO opslab.orders (user_id, amount, status) VALUES (1, ?, 'PAID') RETURNING id",
                Long.class, new BigDecimal(amount));
        return jdbc.queryForObject("""
                INSERT INTO opslab.payments (order_id, amount, status, captured_at)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, orderId, new BigDecimal(amount), status, Timestamp.valueOf(capturedAt));
    }

    private void completedRefund(long paymentId, String amount, LocalDateTime completedAt) {
        refund(paymentId, amount, completedAt, "COMPLETED", "k-" + completedAt);
    }

    /**
     * COMPLETED 가 아닌 환불에도 {@code completed_at} 을 채운다 — 실제 도메인은 그러지 않지만,
     * 여기서 비워 두면 <b>시각이 없어서</b> 걸러진 것인지 <b>상태 때문에</b> 걸러진 것인지 구분이
     * 안 된다. 상태 필터를 지워도 테스트가 초록이면 아무것도 지키지 못한 것이다.
     */
    private void refund(long paymentId, String amount, LocalDateTime completedAt,
                        String status, String idempotencyKey) {
        jdbc.update("""
                INSERT INTO opslab.refunds (payment_id, amount, status, idempotency_key, completed_at)
                VALUES (?, ?, ?, ?, ?)
                """, paymentId, new BigDecimal(amount), status, idempotencyKey,
                Timestamp.valueOf(completedAt));
    }

    private void tender(long paymentId, int sequence, TenderType type, String amount, String status) {
        jdbc.update("""
                INSERT INTO opslab.payment_tenders (payment_id, tender_type, amount, status, sequence)
                VALUES (?, ?, ?, ?, ?)
                """, paymentId, type.name(), new BigDecimal(amount), status, sequence);
    }
}
