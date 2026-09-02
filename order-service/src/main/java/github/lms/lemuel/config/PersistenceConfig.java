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
 *
 * <h2>이 목록은 열거다 — 빠뜨리면 조용히 404 가 된다</h2>
 * {@code @EntityScan}/{@code @EnableJpaRepositories} 에 basePackages 를 적는 순간 Boot 의 기본
 * 스캔(메인 클래스 패키지 이하 전부)은 <b>대체된다</b>. 즉 새 도메인 패키지를 여기 추가하지 않으면
 * 엔티티도 리포지토리도 발견되지 않고, 컴파일은 통과한 채 런타임에만 빈이 없다. 새 패키지를
 * 만들면 <b>두 목록 모두에</b> 추가할 것.
 *
 * <p>반대 방향의 함정도 있다. 여기 적힌 이름이 실재하는 패키지라는 보장은 없다 — 존재하지 않는
 * 패키지는 스캔 대상 0개로 조용히 넘어간다. 2026-09-03 실측에서 {@code chargeback} ·
 * {@code ledger} · {@code payout} · {@code pgreconciliation} · {@code settlement} 다섯 개가
 * <b>디렉터리가 하나도 없는 이름</b>이었다(정산 기능이 settlement 서비스로 넘어가며 코드는
 * 사라졌는데 목록만 남은 것). 지워도 동작은 그대로지만, 남겨 두면 "여기 있다"는 잘못된 신호가
 * 계속 나간다.
 */
@Configuration
@EntityScan(basePackages = {
    "github.lms.lemuel.addressbook",
    "github.lms.lemuel.batch",
    "github.lms.lemuel.bulkorder",
    "github.lms.lemuel.cart",
    "github.lms.lemuel.category",
    "github.lms.lemuel.commoncode",
    "github.lms.lemuel.menu",
    "github.lms.lemuel.rbac",
    "github.lms.lemuel.common",
    "github.lms.lemuel.coupon",
    "github.lms.lemuel.giftcard",
    "github.lms.lemuel.inquiry",
    "github.lms.lemuel.order",
    "github.lms.lemuel.organization",
    "github.lms.lemuel.payment",
    "github.lms.lemuel.point",
    "github.lms.lemuel.product",
    "github.lms.lemuel.review",
    "github.lms.lemuel.shipping",
    "github.lms.lemuel.user",
    "github.lms.lemuel.wishlist",
})
@EnableJpaRepositories(basePackages = {
    "github.lms.lemuel.addressbook",
    "github.lms.lemuel.batch",
    "github.lms.lemuel.bulkorder",
    "github.lms.lemuel.cart",
    "github.lms.lemuel.category",
    "github.lms.lemuel.commoncode",
    "github.lms.lemuel.menu",
    "github.lms.lemuel.rbac",
    "github.lms.lemuel.common",
    "github.lms.lemuel.coupon",
    "github.lms.lemuel.giftcard",
    "github.lms.lemuel.inquiry",
    "github.lms.lemuel.order",
    "github.lms.lemuel.organization",
    "github.lms.lemuel.payment",
    "github.lms.lemuel.point",
    "github.lms.lemuel.product",
    "github.lms.lemuel.review",
    "github.lms.lemuel.shipping",
    "github.lms.lemuel.user",
    "github.lms.lemuel.wishlist",
})
public class PersistenceConfig {
}
