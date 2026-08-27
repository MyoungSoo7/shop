package github.lms.lemuel.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * JPA 엔티티 스캔·리포지토리 활성화 설정.
 *
 * <p>이 설정을 {@code @SpringBootApplication} 메인 클래스에서 분리한 이유: 메인 클래스는 모든
 * 슬라이스 테스트({@code @WebMvcTest} 등)에서 {@code @SpringBootConfiguration} 으로 항상 로드되며,
 * 클래스에 붙은 {@code @EnableJpaRepositories} 가 그때마다 처리되어 웹 슬라이스에도 JPA 리포지토리
 * 빈 생성을 강제한다 → {@code entityManagerFactory} 부재로 컨텍스트 로드 실패. 별도 {@code @Configuration}
 * 으로 옮기면 전체 컨텍스트({@code @SpringBootTest})는 컴포넌트 스캔으로 로드하고, 웹 슬라이스는
 * 타입 필터로 제외해 JPA 없이 부팅한다.
 */
@Configuration
@EntityScan(basePackages = {
    "github.lms.lemuel.bulkorder",
    "github.lms.lemuel.cart",
    "github.lms.lemuel.category",
    "github.lms.lemuel.commoncode",
    "github.lms.lemuel.menu",
    "github.lms.lemuel.rbac",
    "github.lms.lemuel.chargeback",
    "github.lms.lemuel.common",
    "github.lms.lemuel.coupon",
    "github.lms.lemuel.giftcard",
    "github.lms.lemuel.inquiry",
    "github.lms.lemuel.ledger",
    "github.lms.lemuel.order",
    "github.lms.lemuel.organization",
    "github.lms.lemuel.payment",
    "github.lms.lemuel.payout",
    "github.lms.lemuel.pgreconciliation",
    "github.lms.lemuel.point",
    "github.lms.lemuel.product",
    "github.lms.lemuel.review",
    "github.lms.lemuel.settlement",
    "github.lms.lemuel.shipping",
    "github.lms.lemuel.user",
    "github.lms.lemuel.wishlist",
})
@EnableJpaRepositories(basePackages = {
    "github.lms.lemuel.bulkorder",
    "github.lms.lemuel.cart",
    "github.lms.lemuel.category",
    "github.lms.lemuel.commoncode",
    "github.lms.lemuel.menu",
    "github.lms.lemuel.rbac",
    "github.lms.lemuel.chargeback",
    "github.lms.lemuel.common",
    "github.lms.lemuel.coupon",
    "github.lms.lemuel.giftcard",
    "github.lms.lemuel.inquiry",
    "github.lms.lemuel.ledger",
    "github.lms.lemuel.order",
    "github.lms.lemuel.organization",
    "github.lms.lemuel.payment",
    "github.lms.lemuel.payout",
    "github.lms.lemuel.pgreconciliation",
    "github.lms.lemuel.point",
    "github.lms.lemuel.product",
    "github.lms.lemuel.review",
    "github.lms.lemuel.settlement",
    "github.lms.lemuel.shipping",
    "github.lms.lemuel.user",
    "github.lms.lemuel.wishlist",
})
public class PersistenceConfig {
}
