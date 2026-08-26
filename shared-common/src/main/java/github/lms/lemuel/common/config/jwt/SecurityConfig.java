package github.lms.lemuel.common.config.jwt;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final InternalApiKeyFilter internalApiKeyFilter;

    // helm-deploy 차트가 CORS_ORIGINS 환경변수로 주입하므로 cors.origins 우선,
    // 하위호환으로 cors.allowed-origins fallback.
    @org.springframework.beans.factory.annotation.Value("${cors.origins:${cors.allowed-origins:}}")
    private String corsAllowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter,
                          InternalApiKeyFilter internalApiKeyFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.internalApiKeyFilter = internalApiKeyFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }




    /**
     * CORS 설정
     * React 프론트엔드(localhost:3000)와의 통신을 허용합니다.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();

        // CORS origin: 환경변수 우선, 없으면 localhost (개발용)
        if (corsAllowedOrigins != null && !corsAllowedOrigins.isBlank()) {
            configuration.setAllowedOrigins(Arrays.asList(corsAllowedOrigins.split(",")));
        } else {
            configuration.setAllowedOrigins(Arrays.asList(
                    "http://localhost:8089",
                    "http://localhost:3000",
                    "http://localhost:5173",
                    "http://127.0.0.1:3000",
                    "http://127.0.0.1:8089",
                    "http://127.0.0.1:5173"
            ));
        }

        // 허용할 HTTP 메서드
        configuration.setAllowedMethods(Arrays.asList(
                "GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"
        ));

        // 허용할 헤더
        configuration.setAllowedHeaders(Arrays.asList(
                "Authorization",
                "Content-Type",
                "X-Requested-With",
                "Idempotency-Key"  // 환불 API에서 사용
        ));

        // 노출할 헤더
        configuration.setExposedHeaders(Arrays.asList(
                "Authorization",
                "X-Total-Count"
        ));

        // 자격 증명 허용 (쿠키, Authorization 헤더 등)
        configuration.setAllowCredentials(true);

        // 프리플라이트 요청 캐싱 시간 (초)
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);

        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // CORS 설정 활성화
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                // CSRF 비활성화 (JWT 사용 시 불필요)
                .csrf(csrf -> csrf.disable())
                // Form Login 비활성화
                .formLogin(form -> form.disable())
                // HTTP Basic 비활성화
                .httpBasic(basic -> basic.disable())
                // Stateless 세션 관리
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // 요청별 인증 설정
                .authorizeHttpRequests(auth -> auth
                        // CORS Preflight 허용
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // 루트 및 에러 경로
                        .requestMatchers("/", "/error").permitAll()
                        // 인증 불필요 (Public endpoints)
                        .requestMatchers(HttpMethod.POST, "/users").permitAll()               // 회원가입
                        .requestMatchers(HttpMethod.POST, "/auth/login").permitAll()          // 로그인
                        .requestMatchers(HttpMethod.POST, "/auth/dev/**").permitAll()         // 데모 자동로그인/게스트 (lemuel.demo.enabled=true 시)
                        .requestMatchers(HttpMethod.POST, "/users/password-reset/**").permitAll()  // 비밀번호 재설정
                        .requestMatchers("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html").permitAll()
                        // Actuator: 헬스체크 프로브 + prometheus 스크랩 엔드포인트 공개.
                        // /actuator/prometheus 는 메트릭 텍스트만 노출하며 gateway 라우트에 없어 외부 미노출(클러스터 내부 Prometheus 가 스크랩, NetworkPolicy 로 격리 권장).
                        // /actuator/metrics(탐색형 단건 조회 API)는 그대로 인증 필요.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/games/**").permitAll()
                        // 선물 수령 — 받는 사람은 회원이 아니다. 가입을 요구하면 "주소를 주기 싫어서"가
                        // "가입하기 싫어서"로 바뀔 뿐이라 선물이 그대로 죽는다.
                        // 인가는 링크 토큰(256비트, 해시로만 저장)이 대신하고, 배송지를 낼 수 있으려면
                        // 받는 사람 번호로 간 인증번호가 한 단계 더 필요하다. 나가는 값도 최소한이다
                        // (금액 없음, 번호 마스킹). 경로를 /orders 아래에 두지 않은 이유가 이것이다 —
                        // 인증이 필요한 영역 안쪽을 permitAll 로 뚫으면 열린 범위가 눈에 안 보인다.
                        .requestMatchers("/gift-claims/**").permitAll()
                        // 네비게이션 메뉴 — 응답이 호출자 권한으로 이미 걸러져 나가므로 401 을 만들지 않는다.
                        // (로그인 화면에서도 셸이 호출한다. 메뉴 숨김은 UX 이고, 실제 인가는 각 API 가 한다.)
                        .requestMatchers(HttpMethod.GET, "/api/menus/me").permitAll()
                        // 공개 카테고리 API
                        .requestMatchers(HttpMethod.GET, "/categories", "/categories/**").permitAll()
                        // 진열/기획전 공개 조회 — 노출 판정(기간·활성)은 서버가 하므로 미인증에게도 안전하다.
                        .requestMatchers(HttpMethod.GET, "/display-sections", "/display-sections/**").permitAll()
                        // 쿠폰 관련 API
                        .requestMatchers(HttpMethod.GET, "/coupons/available").hasAnyRole("ADMIN", "MANAGER", "USER")
                        .requestMatchers(HttpMethod.GET, "/coupons", "/coupons/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/coupons/*/use").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 쿠폰 생성 — 매처가 없어 anyRequest().authenticated() 로 새고 있었다. 로그인만 하면
                        // 누구나 자기에게 100% 할인 쿠폰을 발행할 수 있었다는 뜻이다. GET 만 열려 있어
                        // "닫혀 있다"고 보이기 쉬웠는데, HttpMethod 를 지정한 매처는 그 메서드에만 걸린다.
                        .requestMatchers(HttpMethod.POST, "/coupons").hasAnyRole("ADMIN", "MANAGER")
                        // 쿠폰 운영 콘솔 — 중단/재개는 나가는 할인을 즉시 멈추는 조작이다.
                        .requestMatchers("/admin/coupons/**").hasAnyRole("ADMIN", "MANAGER")
                        // 관리자 주문 콘솔 (관리자·매니저). `/**` 는 0개 세그먼트도 매치하므로
                        // 목록 자체인 `/orders/admin` 도 이 한 줄이 덮는다 — 없어진
                        // `/orders/admin/all` 처럼 경로마다 줄을 따로 두면, 새 경로를 늘릴 때
                        // 줄 추가를 잊은 그 경로만 anyRequest().authenticated() 로 샌다.
                        .requestMatchers("/orders/admin", "/orders/admin/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/users/admin/all").hasRole("ADMIN")
                        // 배송 상태 전이 — 출고/집화/배송완료/반품은 운영자 조작이다. 특히 반품은
                        // 재고를 되돌리므로 고객이 부를 수 있으면 재고 수량이 조작된다.
                        // (POST /orders/{id}/shipment 는 배송 생성, 그 하위는 상태 전이.)
                        .requestMatchers(HttpMethod.POST, "/orders/*/shipment").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/orders/*/shipment/**").hasAnyRole("ADMIN", "MANAGER")
                        // 배송 조회·배송지 변경은 주문한 본인의 일이라 로그인만 요구하고, "누구의 주문인가"는
                        // 컨트롤러가 주문 소유자와 대조한다(경로 변수 orderId 로는 역할만으론 못 가른다).
                        // 매처가 없던 시절엔 anyRequest().authenticated() 로 떨어져 아무 로그인 사용자나
                        // 남의 수취인 이름·연락처·주소를 읽고 배송지를 바꿀 수 있었다.
                        .requestMatchers(HttpMethod.GET, "/orders/*/shipment").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/orders/*/shipment/address").authenticated()
                        // 관리자 전용 카테고리 API
                        .requestMatchers("/admin/categories/**").hasRole("ADMIN")
                        // 옵션 축/값 카탈로그 — 백필이 상품 옵션 구조를 대량 생성하므로 ADMIN 만.
                        .requestMatchers("/admin/option-catalog/**").hasRole("ADMIN")
                        // 진열 편성 — 무엇이 화면 앞에 오는지를 정하는 콘솔이라 ADMIN 만.
                        .requestMatchers("/admin/display-sections/**").hasRole("ADMIN")
                        /*
                         * 아래 4개는 컨트롤러에 @PreAuthorize("hasRole('ADMIN')") 가 붙어 있어 막혀
                         * 보이지만, 이 애플리케이션에는 @EnableMethodSecurity 가 없다. 메서드 보안이
                         * 켜져 있지 않으면 @PreAuthorize 는 아무 판정도 하지 않는 주석과 같다 —
                         * 실제 인가는 오직 이 매처 목록이 한다. 매처가 없으면 anyRequest().authenticated()
                         * 로 떨어져 로그인만 한 사용자가 권한 부여(rbac)·메뉴 편성·상품 이미지·공통코드를
                         * 조작할 수 있었다. (권한 부여 콘솔은 그 자체가 권한 상승 경로다.)
                         */
                        .requestMatchers("/admin/rbac/**").hasRole("ADMIN")
                        .requestMatchers("/admin/menus/**").hasRole("ADMIN")
                        .requestMatchers("/admin/products/**").hasRole("ADMIN")
                        .requestMatchers("/admin/common-codes/**").hasRole("ADMIN")
                        // 송장 일괄 업로드 - 다건 출고를 한 번에 반영. dryRun 기본값이라 파라미터 누락 호출은 미리보기로 떨어진다.
                        .requestMatchers("/admin/shipments/**").hasAnyRole("ADMIN", "MANAGER")
                        // 셀러 배송비 정책 — 고객에게 청구되는 금액을 직접 바꾸므로 운송장 콘솔과 달리 ADMIN 만.
                        // 포괄 /admin/** 매처는 이 설정에 없다(경로별 열거) — 빠뜨리면 authenticated() 로 샌다.
                        .requestMatchers("/admin/shipping-policies/**").hasRole("ADMIN")
                        // 셀러 등급 콘솔 - 등급은 수수료/정산주기/홀드백을 동시에 바꾸므로 ADMIN 만.
                        .requestMatchers("/admin/seller-tiers/**").hasRole("ADMIN")
                        // 회수 대기 재고 조회 — 배송 후 환불로 원복이 보류된 주문 목록.
                        // 실행 없는 읽기 전용이라 조회 콘솔들과 동일하게 MANAGER 도 허용.
                        .requestMatchers("/admin/stock-reclaim/**").hasAnyRole("ADMIN", "MANAGER")
                        // 주문 시점 동의 이력 조회 — 읽기 전용(고치는 경로가 아예 없다).
                        // 목록 자체인 `/admin/privacy-consents` 도 함께 적는다. 포괄 /admin/** 매처가
                        // 이 설정에 없으므로, 빠뜨리면 authenticated() 로 떨어져 로그인한 아무나
                        // 남의 동의 이력을 훑을 수 있다 — `/orders/admin` 과 같은 이유의 같은 관례다.
                        .requestMatchers("/admin/privacy-consents", "/admin/privacy-consents/**")
                        .hasAnyRole("ADMIN", "MANAGER")
                        // 운영자 전용 — Outbox DLQ 재처리/스킵.
                        // DLQ 실제 경로는 OutboxAdminController 의 /admin/outbox/dlq 계열이라 이 매처가 덮는다.
                        .requestMatchers("/admin/outbox/**").hasRole("ADMIN")
                        .requestMatchers("/admin/pg/**").hasAnyRole("ADMIN", "MANAGER")
                        // 내부 서비스 간 호출 — order 가 자기 대사 합계를 노출(settlement 가 소비, ADR 0020 Phase 5 self-totals).
                        // gateway 미라우팅이지만 NodePort 직노출 대비 InternalApiKeyFilter 가 X-Internal-Api-Key 공유
                        // 시크릿을 검증(미설정 시 통과+경고). 여기선 permitAll 로 두고 게이팅은 필터가 담당. 운영선 NetworkPolicy/mTLS 추가 권장.
                        .requestMatchers("/internal/**").permitAll()
                        // VAN 진입점(card-service 승인·매입·취소·환불) — 게이트웨이 미라우팅이 유일한 방어였고
                        // 매처 목록에 없어 anyRequest().authenticated() 로 떨어져 있었다. 사용자 토큰 하나로
                        // 카드 거래를 위조할 수 있다는 뜻이라, /internal/** 과 동일하게 공유 시크릿 필터에 맡긴다.
                        // (VAN 은 사람이 아니라 기계다 — hasRole 로 여는 문이 아니다.)
                        .requestMatchers("/van/**").permitAll()
                        // 미입금 만료 콘솔 — 주문 취소·재고 원복을 수동 트리거하므로 ADMIN 만.
                        // dryRun 이 기본값이라 파라미터 누락 호출은 미리보기로 떨어진다.
                        .requestMatchers("/admin/payment-expiry/**").hasRole("ADMIN")
                        // 포인트 운영 콘솔 — 수기 지급은 없던 돈을 만들고 소멸 실행은 고객 재산을 지운다.
                        // 포괄 /admin/** 매처는 이 설정에 존재하지 않으므로(경로별 열거 방식) 반드시
                        // 명시해야 한다 — 빠뜨리면 anyRequest().authenticated() 로 새어 일반 사용자도 호출한다.
                        .requestMatchers("/admin/points/**").hasRole("ADMIN")
                        // 기프트카드 콘솔 — 발행은 없던 재산을 만들고 소멸 실행은 고객 재산을 지운다.
                        .requestMatchers("/admin/gift-cards/**").hasRole("ADMIN")
                        // 감사 로그 조회(/admin/audit-logs).
                        // 조회 전용인데도 MANAGER 에게 열지 않는 이유: 이 표면은 "누가 무엇을 조작했는가"
                        // 전체를 보여주므로, 감시받는 사람이 감시 기록을 열람하는 상태가 되면 감사가
                        // 성립하지 않는다. detail_json 에 조작 전후 값이 담기는 것도 같은 이유다.
                        .requestMatchers("/admin/audit-logs/**").hasRole("ADMIN")
                        // 회원 관리 콘솔 — 목록 한 페이지가 이메일·이름·연락처 묶음이고, 역할 변경은
                        // 권한 상승 경로다. MANAGER 에게도 열지 않는다(승인·정지 조작은 기존
                        // /memberships/** 가 MANAGER 까지 허용하지만, 그건 대상이 특정된 단건이다).
                        .requestMatchers("/admin/members/**").hasRole("ADMIN")
                        // 운영자 계정 콘솔 — 권한 있는 계정 목록과 각 계정이 마지막으로 쓰인 시각이다.
                        // 이 목록 자체가 권한 상승 표적 목록이라(어느 관리자 계정이 방치돼 있어 탈취해도
                        // 아무도 눈치채지 못하는가) 회원 콘솔과 같은 이유로 MANAGER 에게도 열지 않는다.
                        // 잠금 해제도 여기에 있다 — 무차별 대입 대응을 사람이 되돌리는 조작이다.
                        .requestMatchers("/admin/operators/**").hasRole("ADMIN")
                        // 판매 통계 콘솔 — 상품 랭킹·카테고리별 분포. 리뷰·환불 콘솔과 달리 CS 업무가
                        // 아니라 경영 정보(무엇이 얼마에 얼마나 팔리는가)라 MANAGER 에게 열지 않는다.
                        .requestMatchers("/admin/sales/**").hasRole("ADMIN")
                        // 리뷰 관리 콘솔 — 다루는 것이 개인정보가 아니라 공개된 게시물이고, 신고 대응은
                        // CS 업무의 일부라 MANAGER 까지 연다(회원 콘솔과 다른 판단).
                        .requestMatchers("/admin/reviews/**").hasAnyRole("ADMIN", "MANAGER")
                        // 환불 콘솔 — 실패/재시도 소진 환불 조회(운영 개입용). 실행 없는 조회라 MANAGER 도 허용
                        .requestMatchers("/admin/refunds/**").hasAnyRole("ADMIN", "MANAGER")
                        // 매출 콘솔 — 기간 수납·환불 합계와 결제수단 구성. 개인정보가 아니라 집계라
                        // 환불 콘솔과 같은 판단으로 MANAGER 까지 연다. 다만 회사 전체 매출이 한 화면에
                        // 나오는 경로이므로 USER 에게는 절대 열리지 않아야 한다 — 매처를 빠뜨리면
                        // anyRequest().authenticated() 로 떨어져 로그인만 하면 누구나 본다.
                        .requestMatchers("/admin/revenue", "/admin/revenue/**").hasAnyRole("ADMIN", "MANAGER")
                        // 작업 큐 콘솔 — 상태별로 밀린 주문 건수와 대기 시간. 리뷰·환불 콘솔과 같은
                        // CS 업무라 MANAGER 까지 연다. 목록이 아니라 집계만 나가지만, 밀린 취소·환불
                        // 신청 건수는 "지금 이 가게가 어디까지 감당하고 있는가"를 그대로 드러낸다.
                        .requestMatchers("/admin/order-queues", "/admin/order-queues/**").hasAnyRole("ADMIN", "MANAGER")
                        // 반품·교환 처리 콘솔 — 승인·거절·회수 확인·환불 실행·환불 계좌 정정. 돈이 나가는
                        // 경로라 환불 콘솔과 같은 등급으로 묶되, 반품 응대는 CS 업무 그 자체라 MANAGER 까지
                        // 연다. 고객이 스스로 하는 신청·철회는 여기가 아니라 /orders/{id}/return-requests
                        // 아래에 있고, 그쪽은 컨트롤러가 주문 주인을 직접 대조한다.
                        .requestMatchers("/admin/return-requests", "/admin/return-requests/**").hasAnyRole("ADMIN", "MANAGER")
                        // 결제 환불 이력 조회 (관리자·매니저·본인) — 더 세밀한 권한은 향후 Audit PR 에서
                        .requestMatchers("/api/payments/*/refunds").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 환불 실행(직접 PG 환불) — "어드민 승인 후 환불" 원칙에 따라 운영자 전용.
                        // 사용자 직접 호출 경로와 운영자 승인 경로를 분리한다(관리자 승인은 /orders/admin/{id}/refund-approve).
                        // 결제 생성/인증/캡처(/payments POST·/authorize·/capture)는 사용자 결제 흐름이라 제한하지 않는다.
                        .requestMatchers(HttpMethod.PATCH, "/payments/*/refund").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/payments/split/*/refund").hasAnyRole("ADMIN", "MANAGER")
                        /*
                         * 2026-08-25 — 이 목록에서 매처 37개를 지웠다. 다시 넣지 말 것.
                         *
                         * 지운 것은 정산·여신·계정계·수신·카드·보험·세무·인사 표면이었다
                         * (/settlements /api/settlements /api/reports /api/ledger /api/account
                         *  /api/banking /api/cards /api/deposits /api/insurance/** /api/tax-invoices
                         *  /admin/{settlements,settlement-projection,commission-rates,ledger-periods,
                         *   monthly-closing,payouts,deposits,chargebacks,recoveries,backfill,tax,
                         *   seller-bank-accounts,seller-tax-profiles,expense-receipts,reconciliation,
                         *   pg-reconciliation,integrity,event-track,dlq,audit-trail} …).
                         *
                         * 이유는 "안 쓰니까"가 아니라 **이 저장소에 그 핸들러가 하나도 없기 때문**이다.
                         * 대응 핸들러가 없는 매처는 아무것도 막지 않는다 — 트래픽이 도달할 수 없으니
                         * 판정될 일이 없다. 남겨두면 "정산도 보호돼 있다"고 읽히는 잘못된 지도가 된다.
                         * 그 도메인들은 경계 밖(정산·여신·계정계)에 있고, 여기는 커머스 코어와 운영만 담는다.
                         *
                         * 되살려야 하는 경우는 하나뿐이다 — **그 경로의 컨트롤러를 이 저장소에 실제로 들일 때**다.
                         * 그때는 매처를 잊어도 CI 가 잡는다: `scripts/harness/test/security-matcher-gate.test.mjs`
                         * 가 "컨트롤러는 있는데 인가 선언이 없는" 상태를 FAIL 로 떨어뜨린다.
                         * (단 그 게이트의 JAVA_SERVICES 는 지금 shared-common 을 훑지 않는다 — 여기에
                         *  관리 컨트롤러를 새로 놓는다면 게이트 대상부터 넓혀야 한다.)
                         */
                        // 나머지는 인증 필요
                        .anyRequest().authenticated()
                )
                // 미인증 요청 → 401, 권한 부족 → 403
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized"))
                        .accessDeniedHandler((request, response, e) ->
                                response.sendError(HttpServletResponse.SC_FORBIDDEN, "Forbidden"))
                )
                // JWT 필터 추가
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                // 내부 API 공유 시크릿 필터 — JWT 보다 먼저 /internal/** 무자격 접근 차단
                .addFilterBefore(internalApiKeyFilter, JwtAuthenticationFilter.class);

        return http.build();
    }
}
