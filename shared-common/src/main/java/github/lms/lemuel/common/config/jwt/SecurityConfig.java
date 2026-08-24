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
                        // 전체 주문/사용자 조회 (관리자·매니저)
                        .requestMatchers("/orders/admin/all").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/orders/admin/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/users/admin/all").hasRole("ADMIN")
                        // 관리자 전용 카테고리 API
                        .requestMatchers("/admin/categories/**").hasRole("ADMIN")
                        // 옵션 축/값 카탈로그 — 백필이 상품 옵션 구조를 대량 생성하므로 ADMIN 만.
                        .requestMatchers("/admin/option-catalog/**").hasRole("ADMIN")
                        // 진열 편성 — 무엇이 화면 앞에 오는지를 정하는 콘솔이라 ADMIN 만.
                        .requestMatchers("/admin/display-sections/**").hasRole("ADMIN")
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
                        // 운영자 전용 — settlement 프로젝션 백필 (Phase 4 Chunk 3)
                        .requestMatchers("/admin/settlement-projection/**").hasRole("ADMIN")
                        // 운영자 전용 — Outbox DLQ / Kafka DLT / PG 라우팅 / PG 정산파일 대사
                        .requestMatchers("/admin/outbox/**").hasRole("ADMIN")
                        .requestMatchers("/admin/dlq/**").hasRole("ADMIN")
                        // 소비 이벤트 3분류(정상·중복·격리) 추적 콘솔 + 격리 재처리 (P0-3)
                        .requestMatchers("/admin/event-track/**").hasRole("ADMIN")
                        .requestMatchers("/admin/pg/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/admin/reconciliation/**").hasAnyRole("ADMIN", "MANAGER")
                        // PG 정산파일 대사 콘솔 — 업로드·승인(역정산 트리거)·거절·조회. 경로가 /admin/pg/** 와
                        // 불일치해 authenticated() 로 새던 것을 형제 recon 콘솔과 동일하게 ADMIN/MANAGER 로 게이트.
                        .requestMatchers("/admin/pg-reconciliation/**").hasAnyRole("ADMIN", "MANAGER")
                        // 정합성 검증 콘솔 — 실행 없는 읽기 전용 조회라 MANAGER 도 허용 (Integrity Suite Phase A)
                        .requestMatchers("/admin/integrity/**").hasAnyRole("ADMIN", "MANAGER")
                        // 내부 서비스 간 호출 — order 가 자기 대사 합계를 노출(settlement 가 소비, ADR 0020 Phase 5 self-totals).
                        // gateway 미라우팅이지만 NodePort 직노출 대비 InternalApiKeyFilter 가 X-Internal-Api-Key 공유
                        // 시크릿을 검증(미설정 시 통과+경고). 여기선 permitAll 로 두고 게이팅은 필터가 담당. 운영선 NetworkPolicy/mTLS 추가 권장.
                        .requestMatchers("/internal/**").permitAll()
                        // VAN 진입점(card-service 승인·매입·취소·환불) — 게이트웨이 미라우팅이 유일한 방어였고
                        // 매처 목록에 없어 anyRequest().authenticated() 로 떨어져 있었다. 사용자 토큰 하나로
                        // 카드 거래를 위조할 수 있다는 뜻이라, /internal/** 과 동일하게 공유 시크릿 필터에 맡긴다.
                        // (VAN 은 사람이 아니라 기계다 — hasRole 로 여는 문이 아니다.)
                        .requestMatchers("/van/**").permitAll()
                        // Payout 콘솔 — 송금 권한은 ADMIN 만 (반송 기록·재지급 포함)
                        .requestMatchers("/admin/payouts/**").hasRole("ADMIN")
                        // 미입금 만료 콘솔 — 주문 취소·재고 원복을 수동 트리거하므로 ADMIN 만.
                        // dryRun 이 기본값이라 파라미터 누락 호출은 미리보기로 떨어진다.
                        .requestMatchers("/admin/payment-expiry/**").hasRole("ADMIN")
                        // 포인트 운영 콘솔 — 수기 지급은 없던 돈을 만들고 소멸 실행은 고객 재산을 지운다.
                        // 포괄 /admin/** 매처는 이 설정에 존재하지 않으므로(경로별 열거 방식) 반드시
                        // 명시해야 한다 — 빠뜨리면 anyRequest().authenticated() 로 새어 일반 사용자도 호출한다.
                        .requestMatchers("/admin/points/**").hasRole("ADMIN")
                        // 기프트카드 콘솔 — 발행은 없던 재산을 만들고 소멸 실행은 고객 재산을 지운다.
                        .requestMatchers("/admin/gift-cards/**").hasRole("ADMIN")
                        // 감사 로그 조회(order=/admin/audit-logs, settlement=/admin/audit-trail).
                        // 조회 전용인데도 MANAGER 에게 열지 않는 이유: 이 표면은 "누가 무엇을 조작했는가"
                        // 전체를 보여주므로, 감시받는 사람이 감시 기록을 열람하는 상태가 되면 감사가
                        // 성립하지 않는다. detail_json 에 조작 전후 값이 담기는 것도 같은 이유다.
                        .requestMatchers("/admin/audit-logs/**", "/admin/audit-trail/**").hasRole("ADMIN")
                        // 회원 관리 콘솔 — 목록 한 페이지가 이메일·이름·연락처 묶음이고, 역할 변경은
                        // 권한 상승 경로다. MANAGER 에게도 열지 않는다(승인·정지 조작은 기존
                        // /memberships/** 가 MANAGER 까지 허용하지만, 그건 대상이 특정된 단건이다).
                        .requestMatchers("/admin/members/**").hasRole("ADMIN")
                        // 리뷰 관리 콘솔 — 다루는 것이 개인정보가 아니라 공개된 게시물이고, 신고 대응은
                        // CS 업무의 일부라 MANAGER 까지 연다(회원 콘솔과 다른 판단).
                        .requestMatchers("/admin/reviews/**").hasAnyRole("ADMIN", "MANAGER")
                        // 정산 배치 재실행 콘솔 — 확정·홀드백 해제·지급 실행을 수동 트리거하므로
                        // 조회 콘솔과 달리 MANAGER 에게 열지 않는다. 일자 게이트(미래·소급 상한)는 도메인이 강제.
                        // 수수료율 정책 — 정산 금액을 직접 바꾸므로 조회 콘솔과 달리 ADMIN 만.
                        .requestMatchers("/admin/commission-rates/**").hasRole("ADMIN")
                        .requestMatchers("/admin/settlements/**").hasRole("ADMIN")
                        // 원장 기간 마감·정보계 월마감 — 두 컨트롤러 javadoc 이 "/admin/** ADMIN 게이트 상속"이라
                        // 적었지만 그런 포괄 매처는 존재한 적이 없어(경로별 열거 방식) authenticated() 로 새고 있었다.
                        // 기간 마감은 재개봉이 없고 월마감은 마트를 통째로 교체하므로 형제 실행 콘솔과 동일하게 ADMIN 만.
                        .requestMatchers("/admin/ledger-periods/**").hasRole("ADMIN")
                        .requestMatchers("/admin/monthly-closing/**").hasRole("ADMIN")
                        // 셀러 지급 계좌 레지스트리 — 등록·정정(PII). 셀러 식별자를 관리자 입력으로 받으므로
                        // ADMIN/MANAGER 게이트로 IDOR 방지 (Seed D1).
                        .requestMatchers("/admin/seller-bank-accounts/**").hasAnyRole("ADMIN", "MANAGER")
                        // 셀러 세무 프로필 레지스트리(PII 사업자등록번호) — 셀러 식별자를 관리자 입력으로 받으므로
                        // ADMIN/MANAGER 게이트로 IDOR 방지 (Seed B2, ADR 0029).
                        .requestMatchers("/admin/seller-tax-profiles/**").hasAnyRole("ADMIN", "MANAGER")
                        // 세무 산출물 운영 콘솔 — 세무 전표 전기·세금계산서 발행·3자 대사 (Seed B2).
                        .requestMatchers("/admin/tax/**").hasAnyRole("ADMIN", "MANAGER")
                        // 세금계산서 셀러 다운로드 — JWT 주체(userId) 파생 + 소유권 대조(403)로 IDOR 방지.
                        .requestMatchers("/api/tax-invoices/**").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 셀러 지급 계좌 셀프서비스 — 셀러 식별자를 요청에서 받지 않고 JWT 주체(userId)에서만
                        // 파생하므로 인증 사용자(USER) 허용으로 IDOR 원천 차단 (관리자 대행은 /admin/seller-bank-accounts).
                        .requestMatchers("/api/seller/bank-account").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // Chargeback 콘솔 — 셀러 환수 결정은 ADMIN 만
                        .requestMatchers("/admin/chargebacks/**").hasRole("ADMIN")
                        // 백필 콘솔 — 원장 역분개·Payout 누락 보정 작업은 ADMIN 만
                        .requestMatchers("/admin/backfill/**").hasRole("ADMIN")
                        // 지급후 회수 채권·상계 조회 콘솔 — 읽기 전용이라 MANAGER 도 허용 (seed-p0-6)
                        .requestMatchers("/admin/recoveries/**").hasAnyRole("ADMIN", "MANAGER")
                        // 기업 신용대출 실행(실자금 지급) — 승인·실행 권한은 ADMIN 만.
                        // 신용평가 조회(/credit)·신청(POST /loans/corporate)·목록 조회는 인증 사용자(CEO) 허용.
                        .requestMatchers(HttpMethod.POST, "/loans/corporate/*/disburse").hasRole("ADMIN")
                        // 환불 콘솔 — 실패/재시도 소진 환불 조회(운영 개입용). 실행 없는 조회라 MANAGER 도 허용
                        .requestMatchers("/admin/refunds/**").hasAnyRole("ADMIN", "MANAGER")
                        // 정산 관련 API (관리자·매니저)
                        .requestMatchers("/settlements/**").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/api/settlements/**").hasAnyRole("ADMIN", "MANAGER")
                        // 재무/자금흐름 리포트 (관리자·매니저)
                        .requestMatchers("/api/reports/**").hasAnyRole("ADMIN", "MANAGER")
                        // 원장(Ledger) 조회 — 회계 감사용 (관리자·매니저)
                        .requestMatchers("/api/ledger/**").hasAnyRole("ADMIN", "MANAGER")
                        // 계정계(GL) 조회 콘솔 — owner 잔액·분개·전사 집계·시산표는 회계 백오피스라 관리자·매니저 전용
                        // (프론트도 /admin/ceo/accounts 를 AdminManagerRoute 로 보호). 무권한 노출(owner IDOR·전사 집계) 차단.
                        .requestMatchers("/api/account/**").hasAnyRole("ADMIN", "MANAGER")
                        // 셀러 예치금 운영 콘솔 — 잔고를 직접 움직이는 수기 경로라 ADMIN 만.
                        // (자동 경로는 settlement.confirmed·payout.completed 컨슈머다)
                        .requestMatchers("/admin/deposits/**").hasRole("ADMIN")
                        // 법인카드 영수증 리뷰 콘솔(ADR 0036) — 대사 종결은 승인 게이트를 여는 운영 판단이라 ADMIN 만.
                        .requestMatchers("/admin/expense-receipts/**").hasRole("ADMIN")
                        // 청약서류 리뷰 큐(ADR 0036) — 계약자·피보험자 성명(PII)이 실려 언더라이팅과 같은 등급.
                        .requestMatchers("/api/insurance/application-documents/**").hasAnyRole("ADMIN", "MANAGER")
                        // 셀러 예치금 조회 — 남의 계좌를 경로로 지정하는 형태라 운영자 전용.
                        // 본인 조회(/api/deposits/accounts/me)는 아래 authenticated 로 열되, sellerId 를
                        // 경로가 아니라 JWT 주체에서만 파생해 IDOR 을 원천 차단한다(/api/seller/bank-account 와 동일).
                        .requestMatchers(HttpMethod.GET, "/api/deposits/accounts/me").authenticated()
                        .requestMatchers("/api/deposits/**").hasAnyRole("ADMIN", "MANAGER")
                        // 법인카드 — 인증만 요구하고, 조직 역할(OWNER/MANAGER/STAFF) 판정은
                        // card-service 의 CardOrgAuthorizer 가 멤버십 프로젝션으로 수행한다(IDOR 방지).
                        .requestMatchers("/api/cards/**").authenticated()
                        // 수신 상품(정기예금·적금·퇴직연금) — 계약 주체가 가입자 본인이라 인증만 요구하고,
                        // 소유권(depositorId == JWT userId) 판정은 각 상품 서비스가 수행한다(IDOR 방지). /api/cards/** 와 같은 방식.
                        // 단, 아래 두 경로는 "기관이 돈을 인식·지급하는" 행위라 가입자에게 열어두면 임의 증액이 된다.
                        // 운용수익 인식(운용사 통지)과 수급 지급(지급 집행)은 /admin/payouts/** 와 같은 운영자 권한으로 막는다.
                        .requestMatchers(HttpMethod.POST, "/api/banking/pensions/*/interest-settlements")
                        .hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/api/banking/pensions/*/benefit-payments")
                        .hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers("/api/banking/**").authenticated()
                        // 셀러 예치금 — 읽기 표면(/api)과 잔고를 움직이는 표면(/admin)을 경로로 분리한다.
                        // 본인 조회는 경로에 sellerId 가 없고 JWT 주체에서 파생하므로 인증만 요구하고,
                        // 임의 셀러 조회는 타인 잔고 열람이라 운영자 전용이다. 순서가 곧 규칙이니
                        // /accounts/me 매처가 와일드카드보다 먼저 와야 한다.
                        .requestMatchers(HttpMethod.GET, "/api/deposits/accounts/me").authenticated()
                        .requestMatchers("/api/deposits/**").hasAnyRole("ADMIN", "MANAGER")
                        // 수기 입출금·선점·상계는 실자금 원장 조작이라 MANAGER 도 제외하고 ADMIN 만
                        // (/admin/payouts/** 와 같은 기준).
                        .requestMatchers("/admin/deposits/**").hasRole("ADMIN")
                        // 결제 환불 이력 조회 (관리자·매니저·본인) — 더 세밀한 권한은 향후 Audit PR 에서
                        .requestMatchers("/api/payments/*/refunds").hasAnyRole("ADMIN", "MANAGER", "USER")
                        // 환불 실행(직접 PG 환불) — "어드민 승인 후 환불" 원칙에 따라 운영자 전용.
                        // 사용자 직접 호출 경로와 운영자 승인 경로를 분리한다(관리자 승인은 /orders/admin/{id}/refund-approve).
                        // 결제 생성/인증/캡처(/payments POST·/authorize·/capture)는 사용자 결제 흐름이라 제한하지 않는다.
                        .requestMatchers(HttpMethod.PATCH, "/payments/*/refund").hasAnyRole("ADMIN", "MANAGER")
                        .requestMatchers(HttpMethod.POST, "/payments/split/*/refund").hasAnyRole("ADMIN", "MANAGER")
                        // 보험 언더라이팅(심사 착수·승인·반려) — 승인은 계약을 발행하고 수수료 12회를 확정한다.
                        // 매처 목록에 없어 anyRequest().authenticated() 로 떨어져 있었고, 그 결과 청약 UUID 만
                        // 알면 아무 로그인 사용자나 계약을 발행시킬 수 있었다. 접수자(FC)와 심사자는 같은
                        // 권한일 수 없으므로 백오피스 역할로 분리한다.
                        .requestMatchers(HttpMethod.POST, "/api/insurance/applications/*/review",
                                "/api/insurance/applications/*/approve",
                                "/api/insurance/applications/*/reject").hasAnyRole("ADMIN", "MANAGER")
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
