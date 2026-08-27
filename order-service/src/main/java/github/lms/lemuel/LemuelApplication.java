package github.lms.lemuel;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

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
        "github.lms.lemuel.report",
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
        "github.lms.lemuel.common",
        // ADR 0020 Phase 5.5 — settlement 분리 완료. settlement/ledger/payout/chargeback/
        // pgreconciliation 코드는 settlement-service 로 이전돼 order 소스에 존재하지 않으므로
        // 과거의 번들 스캔 엔트리(아무 빈도 못 잡던 잔재)를 제거했다. opslab 의 잔여 테이블 정리는
        // docs/runbook/settlement-db-decommission.md 참조.
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
