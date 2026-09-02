package github.lms.lemuel;

import github.lms.lemuel.common.config.kafka.KafkaConsumerErrorHandlingConfig;
import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DLT 배선을 <b>명시적으로</b> 끌어온다.
 *
 * <p>이 서비스의 컴포넌트 스캔은 루트가 아니라 아래 열거식이다. 지금은 목록에
 * {@code github.lms.lemuel.common} 이 들어 있어 결과적으로 배선이 잡히지만, 그건 우연에 가깝다 —
 * 누군가 스캔 목록을 정리하면서 common 을 빼는 순간 배선이 사라지고, 그래도 <b>기동은 성공한다</b>.
 * Spring Kafka 기본 핸들러가 대신 뜨고 재시도 소진 메시지를 조용히 skip 하기 때문이다(사실상 유실).
 * order 에 첫 {@code @KafkaListener}({@code MarketingRewardConsumer})가 붙는 지금 못을 박는다.
 */
@Import(KafkaConsumerErrorHandlingConfig.class)
@SpringBootApplication(
    scanBasePackages = {
        "github.lms.lemuel.config",
        "github.lms.lemuel.user",
        "github.lms.lemuel.order",
        "github.lms.lemuel.cart",
        // 대량주문 초안(업로드→검증→확정) — 이 스캔이 빠지면 /api/bulk-orders 가 조용히 404 가 되고,
        // 확정 어댑터가 order/shipping 유스케이스를 못 찾아 기동 시점에는 아무 신호도 나지 않는다.
        "github.lms.lemuel.bulkorder",
        "github.lms.lemuel.shipping",
        "github.lms.lemuel.payment",
        "github.lms.lemuel.product",
        "github.lms.lemuel.category",
        "github.lms.lemuel.coupon",
        "github.lms.lemuel.review",
        "github.lms.lemuel.game",
        // 관리자 시스템 (RBAC·메뉴·공통코드) — 스캔 누락으로 컨트롤러 미등록이던 것을 배선
        "github.lms.lemuel.menu",
        "github.lms.lemuel.commoncode",
        "github.lms.lemuel.rbac",
        // 감사 로그 조회 콘솔 — 적재는 shared-common(common.audit)이 오래전부터 했지만 읽는 경로가
        // 없었다. 이 스캔이 빠지면 /admin/audit-logs 가 조용히 404 가 된다(적재는 계속되므로 아무도
        // 눈치채지 못한다).
        "github.lms.lemuel.auditconsole",
        // 셀러 등급 산정(ADR 0031) — 컨트롤러·스케줄러·JdbcTemplate 어댑터가 이 스캔에 걸린다.
        // 빠지면 gateway 라우트는 있는데 핸들러가 없어 /admin/seller-tiers 가 조용히 404 가 된다.
        "github.lms.lemuel.sellertier",
        // 포인트 원장 — 결제의 POINT 텐더가 이 스캔에 걸린 유스케이스를 호출한다.
        // 빠지면 텐더 어댑터가 빈을 찾지 못해 결제 경로가 기동 시점에 깨진다.
        "github.lms.lemuel.point",
        // 기프트카드 원장 — 결제의 GIFT_CARD 텐더가 이 스캔에 걸린 유스케이스를 호출한다.
        "github.lms.lemuel.giftcard",
        // organization — 셀러/기업 조직·멤버십 슬라이스(ADR 0042 흡수). 이 스캔이 빠지면
        // /api/organizations/** 가 조용히 404 가 되고 UseCase 빈이 없어 컨텍스트가 뜨지 않는다.
        "github.lms.lemuel.organization",
        // 찜(위시리스트) — 이 스캔이 빠지면 /users/*/wishlist 가 조용히 404 가 된다. 컨트롤러가
        // 등록되지 않아도 기동은 성공하므로(정적 리소스 핸들러가 대신 받는다) 아무 신호도 없다.
        "github.lms.lemuel.wishlist",
        // 문의(상품 문의·주문 문의·1:1) — 찜과 같은 이유로 빠지면 /inquiries 와 /admin/inquiries 가
        // 조용히 404 가 된다. 여기 더하는 것만으로는 부족하다 — PersistenceConfig 의 @EntityScan·
        // @EnableJpaRepositories 도 열거식이라 그쪽을 빠뜨리면 테스트는 통과하고 기동만 깨진다.
        "github.lms.lemuel.inquiry",
        // 배송지 주소록 — 위 둘과 같은 이유로 빠지면 /users/*/shipping-addresses 가 조용히 404 가
        // 된다. 아래 PersistenceConfig 의 @EntityScan·@EnableJpaRepositories 도 함께 고쳐야 한다.
        "github.lms.lemuel.addressbook",
        // 배치 실행 원장 — 스케줄러 전부가 여기 BatchRunRecorder 를 생성자로 받는다. 이 줄이 빠지면
        // 404 로 조용히 끝나는 위 것들과 달리 <b>컨텍스트가 아예 안 뜬다</b>(NoSuchBeanDefinition).
        // 실제로 이 줄을 빠뜨린 채 PersistenceConfig 쪽만 고쳐 통합테스트 9건이 한꺼번에 죽었다 —
        // 위 inquiry 주석이 경고하는 함정의 <i>반대 방향</i>이다. 두 목록은 언제나 함께 고친다.
        "github.lms.lemuel.batch",
        // 만료 예고 알림 — 스케줄러·JDBC 어댑터가 이 스캔에 걸린다. JPA 엔티티가 없어
        // PersistenceConfig 에는 <b>일부러 넣지 않았다</b>. 두 목록이 항상 같지는 않다는 뜻이다.
        "github.lms.lemuel.expirynotice",
        "github.lms.lemuel.common",
        // ADR 0020 Phase 5.5 — settlement 분리 완료. settlement/ledger/payout/chargeback/
        // pgreconciliation 코드는 settlement-service 로 이전돼 order 소스에 존재하지 않으므로
        // 과거의 번들 스캔 엔트리(아무 빈도 못 잡던 잔재)를 제거했다. opslab 의 잔여 테이블 정리는
        // docs/runbook/settlement-db-decommission.md 참조.
        // report 도 같은 이유로 뺐다 — 소스 트리에 그 패키지가 아예 없어 0개를 스캔하던 죽은 줄이다.
        // 죽은 열거 항목은 무해해 보이지만 "그 기능이 배선돼 있다"는 거짓 신호를 남긴다.
        // reservation 도 reservation-service(독립 MSA, :8083, gateway 라우팅)가 소유한다. order 소스에
        // 레거시 reservation 패키지가 남아있으나 스캔에서 제외한다 — 컴포넌트(Adapter)는 스캔되는데
        // JPA 리포지토리는 PersistenceConfig 의 @EnableJpaRepositories 스코프 밖이라 빈 미생성 →
        // fresh 부팅이 깨지던 문제를 스캔 제외로 해소.
    }
)
@EnableScheduling
public class LemuelApplication {

    public static void main(String[] args) {
        Dotenv dotenv = Dotenv.configure()
            .ignoreIfMissing()
            .load();

        dotenv.entries().forEach(e ->
            System.setProperty(e.getKey(), e.getValue())
        );
        SpringApplication.run(LemuelApplication.class, args);
    }

}
