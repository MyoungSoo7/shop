package github.lms.lemuel.order.application.service;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.order.application.port.in.GetPrivacyConsentUseCase;
import github.lms.lemuel.order.application.port.in.RecordOrderConsentUseCase;
import github.lms.lemuel.order.application.port.out.LoadOrderPrivacyConsentPort;
import github.lms.lemuel.order.application.port.out.LoadPrivacyConsentTermsPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPrivacyConsentPort;
import github.lms.lemuel.order.domain.OrderPrivacyConsent;
import github.lms.lemuel.order.domain.PrivacyConsentTerms;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import github.lms.lemuel.order.domain.exception.PrivacyConsentRequiredException;
import github.lms.lemuel.order.domain.exception.PrivacyConsentTermsStaleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 주문 시점 동의의 검증·기록·열람.
 *
 * <p>이 서비스가 하는 판단은 하나다 — <b>지금 유효한 문안</b>과 <b>올라온 체크</b>를 맞춰 보고,
 * 어긋나면 주문을 무른다. 어긋남에는 두 종류가 있고 둘을 구분해 다른 상태코드로 돌려준다.
 * 필수 항목을 안 눌렀으면 사용자가 눌러야 할 일(400)이고, 버전이 다르면 화면을 다시 받아야 할
 * 일(409)이다. 둘을 400 으로 뭉치면 클라이언트가 "체크하라"고 안내하는데 정작 체크는 이미 돼
 * 있어서 사용자가 빠져나올 수 없는 화면이 된다.
 */
@Service
public class OrderPrivacyConsentService implements RecordOrderConsentUseCase, GetPrivacyConsentUseCase {

    /** 열람 목록의 상한 — 호출자가 더 큰 값을 줘도 여기서 잘린다. */
    private static final int MAX_VIEW_LIMIT = 500;

    private final LoadPrivacyConsentTermsPort loadTermsPort;
    private final SaveOrderPrivacyConsentPort saveConsentPort;
    private final LoadOrderPrivacyConsentPort loadConsentPort;
    private final Clock clock;

    public OrderPrivacyConsentService(LoadPrivacyConsentTermsPort loadTermsPort,
                                      SaveOrderPrivacyConsentPort saveConsentPort,
                                      LoadOrderPrivacyConsentPort loadConsentPort,
                                      Clock clock) {
        this.loadTermsPort = loadTermsPort;
        this.saveConsentPort = saveConsentPort;
        this.loadConsentPort = loadConsentPort;
        this.clock = clock;
    }

    /**
     * {@inheritDoc}
     *
     * <p>{@code @Transactional} 이지만 새 트랜잭션을 여는 일은 거의 없다 — 주문 생성이 이미 열어
     * 둔 것에 참여한다(전파 기본값 REQUIRED). 여기서 던지는 예외가 주문까지 되돌리는 것이 의도다.
     */
    @Override
    @Transactional
    public List<OrderPrivacyConsent> record(RecordCommand command) {
        if (command == null || command.orderId() == null || command.userId() == null) {
            throw new OrderInvariantViolationException("동의 기록에는 주문과 동의자가 있어야 합니다");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        List<PrivacyConsentTerms> effective = loadTermsPort.findEffectiveAt(now);
        if (effective.isEmpty()) {
            // 문안이 하나도 없으면 무엇에 동의했는지 적을 수 없다. 조용히 통과시키면 동의 없는
            // 주문이 쌓이는데, 그게 바로 이 기능이 막으려는 상태다. 설정 사고이므로 500 이다 —
            // 클라이언트가 고칠 수 있는 것이 아무것도 없다.
            throw new BusinessException(ErrorCode.INTERNAL_ERROR,
                    "동의 문안이 설정되어 있지 않아 주문을 받을 수 없습니다.");
        }

        Map<String, Acceptance> submitted = index(command.acceptances());
        rejectUnknownCodes(submitted, effective);

        List<String> unsatisfied = new ArrayList<>();
        List<OrderPrivacyConsent> records = new ArrayList<>();
        for (PrivacyConsentTerms terms : effective) {
            Acceptance acceptance = submitted.get(terms.getCode());
            if (acceptance == null) {
                // 선택 항목을 안 보냈다면 안 물어본 것으로 본다. 없는 대답을 "거절"로 지어내면
                // 나중에 "물었는데 거절했다"는 거짓 이력이 된다.
                if (terms.isRequired()) {
                    unsatisfied.add(terms.getCode());
                }
                continue;
            }
            requireSameVersion(terms, acceptance);
            if (terms.isRequired() && !acceptance.agreed()) {
                unsatisfied.add(terms.getCode());
                continue;
            }
            records.add(terms.accept(command.orderId(), command.userId(), acceptance.agreed(),
                    now, command.ipAddress()));
        }
        if (!unsatisfied.isEmpty()) {
            throw new PrivacyConsentRequiredException(unsatisfied);
        }
        return saveConsentPort.saveAll(records);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrivacyConsentTerms> currentTerms() {
        return loadTermsPort.findEffectiveAt(LocalDateTime.now(clock)).stream()
                // 필수가 먼저, 그 안에서는 코드순. 화면이 정렬을 다시 하지 않아도 되도록 여기서
                // 정해 둔다 — 정렬이 화면마다 다르면 같은 목록이 자리마다 다르게 보인다.
                .sorted(Comparator.comparing(PrivacyConsentTerms::isRequired).reversed()
                        .thenComparing(PrivacyConsentTerms::getCode))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsentView> ofOrder(Long orderId) {
        if (orderId == null) {
            return List.of();
        }
        List<OrderPrivacyConsent> consents = loadConsentPort.findByOrderId(orderId);
        // 같은 (코드, 버전) 을 여러 번 조회하지 않도록 한 번만 읽어 둔다. 한 주문의 동의는
        // 많아야 몇 건이지만, 관리자 목록이 같은 코드를 부르므로 습관을 여기서 맞춘다.
        Map<String, Optional<PrivacyConsentTerms>> cache = new HashMap<>();
        return consents.stream().map(consent -> toView(consent, cache)).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsentView> ofUser(Long userId, int limit) {
        if (userId == null) {
            return List.of();
        }
        Map<String, Optional<PrivacyConsentTerms>> cache = new HashMap<>();
        return loadConsentPort.findByUserId(userId, capped(limit)).stream()
                .map(consent -> toView(consent, cache))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConsentView> ofTermsVersion(String termsCode, int termsVersion, int limit) {
        if (termsCode == null || termsCode.isBlank() || termsVersion <= 0) {
            return List.of();
        }
        Map<String, Optional<PrivacyConsentTerms>> cache = new HashMap<>();
        return loadConsentPort.findByTermsCodeAndVersion(termsCode, termsVersion, capped(limit)).stream()
                .map(consent -> toView(consent, cache))
                .toList();
    }

    private ConsentView toView(OrderPrivacyConsent consent,
                               Map<String, Optional<PrivacyConsentTerms>> cache) {
        String key = consent.getTermsCode() + "#" + consent.getTermsVersion();
        Optional<PrivacyConsentTerms> terms = cache.computeIfAbsent(key,
                ignored -> loadTermsPort.findByCodeAndVersion(consent.getTermsCode(), consent.getTermsVersion()));
        // 문안 행이 아예 사라졌으면 대조할 대상이 없다. 그때도 "같다"고 말하지 않는다 —
        // 확인되지 않은 것을 확인된 것으로 보이게 하면 이 칸의 의미가 없어진다.
        return new ConsentView(consent, terms.map(consent::matchesBodyOf).orElse(false));
    }

    private static Map<String, Acceptance> index(List<Acceptance> acceptances) {
        Map<String, Acceptance> byCode = new LinkedHashMap<>();
        if (acceptances == null) {
            return byCode;
        }
        for (Acceptance acceptance : acceptances) {
            if (acceptance == null || acceptance.termsCode() == null || acceptance.termsCode().isBlank()) {
                throw new OrderInvariantViolationException("동의 항목에 문안 코드가 없습니다");
            }
            String code = acceptance.termsCode().trim();
            // 같은 코드가 두 번 오면 어느 쪽이 사용자의 뜻인지 알 수 없다. 뒤엣것으로 덮으면
            // true 뒤에 false 가 붙어 오는 조작이 조용히 통한다.
            if (byCode.putIfAbsent(code, acceptance) != null) {
                throw new OrderInvariantViolationException("같은 문안에 대한 동의가 두 번 왔습니다: " + code);
            }
        }
        return byCode;
    }

    private static void rejectUnknownCodes(Map<String, Acceptance> submitted,
                                           List<PrivacyConsentTerms> effective) {
        Set<String> known = new HashSet<>();
        for (PrivacyConsentTerms terms : effective) {
            known.add(terms.getCode());
        }
        for (Map.Entry<String, Acceptance> entry : submitted.entrySet()) {
            if (!known.contains(entry.getKey())) {
                // 지금 유효하지 않은 문안에 동의했다는 것은 낡은 화면을 보고 있다는 뜻이다.
                throw new PrivacyConsentTermsStaleException(
                        entry.getKey(), entry.getValue().termsVersion(), null);
            }
        }
    }

    private static void requireSameVersion(PrivacyConsentTerms terms, Acceptance acceptance) {
        if (acceptance.termsVersion() == null || acceptance.termsVersion() != terms.getVersion()) {
            throw new PrivacyConsentTermsStaleException(
                    terms.getCode(), acceptance.termsVersion(), terms.getVersion());
        }
    }

    private static int capped(int limit) {
        return Math.clamp(limit, 1, MAX_VIEW_LIMIT);
    }
}
