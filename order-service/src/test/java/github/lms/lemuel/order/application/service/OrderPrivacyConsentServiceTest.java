package github.lms.lemuel.order.application.service;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;
import github.lms.lemuel.order.application.port.in.GetPrivacyConsentUseCase.ConsentView;
import github.lms.lemuel.order.application.port.in.RecordOrderConsentUseCase.Acceptance;
import github.lms.lemuel.order.application.port.in.RecordOrderConsentUseCase.RecordCommand;
import github.lms.lemuel.order.application.port.out.LoadOrderPrivacyConsentPort;
import github.lms.lemuel.order.application.port.out.LoadPrivacyConsentTermsPort;
import github.lms.lemuel.order.application.port.out.SaveOrderPrivacyConsentPort;
import github.lms.lemuel.order.domain.ConsentType;
import github.lms.lemuel.order.domain.OrderPrivacyConsent;
import github.lms.lemuel.order.domain.PrivacyConsentTerms;
import github.lms.lemuel.order.domain.exception.OrderInvariantViolationException;
import github.lms.lemuel.order.domain.exception.PrivacyConsentRequiredException;
import github.lms.lemuel.order.domain.exception.PrivacyConsentTermsStaleException;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 동의의 검증·기록·열람.
 *
 * <p>이 서비스가 가르는 두 가지를 특히 본다.
 * <ul>
 *   <li><b>400 과 409 를 나눈다</b> — 필수 항목을 안 눌렀으면 사용자가 눌러야 할 일이고,
 *       버전이 다르면 화면을 다시 받아야 할 일이다. 뭉치면 이미 체크한 사용자가 "체크하라"는
 *       안내를 받고 화면에서 빠져나올 수 없게 된다.</li>
 *   <li><b>안 보낸 것을 거절로 지어내지 않는다</b> — 선택 항목이 없으면 "묻지 않았다"이지
 *       "물었고 거절했다"가 아니다. 나중에 광고 발송의 근거를 따질 때 필요한 것이 그 구분이다.</li>
 * </ul>
 */
class OrderPrivacyConsentServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 10, 0);
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private LoadPrivacyConsentTermsPort loadTermsPort;
    private SaveOrderPrivacyConsentPort saveConsentPort;
    private LoadOrderPrivacyConsentPort loadConsentPort;
    private OrderPrivacyConsentService service;

    @BeforeEach
    void setUp() {
        loadTermsPort = mock(LoadPrivacyConsentTermsPort.class);
        saveConsentPort = mock(SaveOrderPrivacyConsentPort.class);
        loadConsentPort = mock(LoadOrderPrivacyConsentPort.class);
        Clock clock = Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        service = new OrderPrivacyConsentService(loadTermsPort, saveConsentPort, loadConsentPort, clock);
        // 저장은 받은 그대로 돌려준다 — 여기서 보고 싶은 것은 "무엇을 저장하려 했는가"다.
        when(saveConsentPort.saveAll(any())).thenAnswer(call -> call.getArgument(0));
    }

    private static PrivacyConsentTerms terms(String code, int version, ConsentType type,
                                             boolean required, String bodySha256) {
        String recipient = type.requiresRecipient() ? "배송업체" : null;
        return PrivacyConsentTerms.restore(1L, code, version, type, "제목", recipient,
                "목적", "항목", "보유", "전문", bodySha256, required,
                NOW.minusDays(30), null, NOW.minusDays(30));
    }

    private static PrivacyConsentTerms requiredCollection() {
        return terms("COLLECTION_USE_ORDER", 1, ConsentType.COLLECTION_USE, true, "hash-collection");
    }

    private static PrivacyConsentTerms requiredThirdParty() {
        return terms("THIRD_PARTY_DELIVERY", 1, ConsentType.THIRD_PARTY_PROVISION, true, "hash-delivery");
    }

    private static PrivacyConsentTerms optionalMarketing() {
        return terms("MARKETING_MESSAGE", 1, ConsentType.MARKETING, false, "hash-marketing");
    }

    private void effective(PrivacyConsentTerms... catalog) {
        when(loadTermsPort.findEffectiveAt(any())).thenReturn(List.of(catalog));
    }

    private static RecordCommand command(Acceptance... acceptances) {
        return new RecordCommand(7L, 42L, Arrays.asList(acceptances), "203.0.113.7");
    }

    @SuppressWarnings("unchecked")
    private List<OrderPrivacyConsent> captureSaved() {
        ArgumentCaptor<List<OrderPrivacyConsent>> captor = ArgumentCaptor.forClass(List.class);
        verify(saveConsentPort).saveAll(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("기록할 때")
    class Recording {

        @Test @DisplayName("필수 둘에 동의하고 선택을 거절하면 세 줄이 모두 남는다")
        void recordsAgreementsAndRefusals() {
            effective(requiredCollection(), requiredThirdParty(), optionalMarketing());

            List<OrderPrivacyConsent> saved = service.record(command(
                    new Acceptance("COLLECTION_USE_ORDER", 1, true),
                    new Acceptance("THIRD_PARTY_DELIVERY", 1, true),
                    new Acceptance("MARKETING_MESSAGE", 1, false)));

            assertThat(saved).hasSize(3);
            assertThat(saved).extracting(OrderPrivacyConsent::getTermsCode)
                    .containsExactly("COLLECTION_USE_ORDER", "THIRD_PARTY_DELIVERY", "MARKETING_MESSAGE");
            assertThat(saved).extracting(OrderPrivacyConsent::isAgreed)
                    .containsExactly(true, true, false);
            assertThat(saved).allSatisfy(consent -> {
                assertThat(consent.getOrderId()).isEqualTo(7L);
                assertThat(consent.getUserId()).isEqualTo(42L);
                // 시각과 접속지는 서버가 정한다 — 클라이언트가 보낸 값을 그대로 믿으면 이력이 아니다.
                assertThat(consent.getAgreedAt()).isEqualTo(NOW);
                assertThat(consent.getIpAddress()).isEqualTo("203.0.113.7");
            });
        }

        @Test @DisplayName("안 보낸 선택 항목은 거절로 지어내지 않는다")
        void missingOptionalIsNotRecordedAsRefusal() {
            effective(requiredCollection(), optionalMarketing());

            List<OrderPrivacyConsent> saved = service.record(command(
                    new Acceptance("COLLECTION_USE_ORDER", 1, true)));

            assertThat(saved).extracting(OrderPrivacyConsent::getTermsCode)
                    .containsExactly("COLLECTION_USE_ORDER");
        }

        @Test @DisplayName("필수 항목이 아예 안 오면 400(PRIVACY_CONSENT_REQUIRED)")
        void missingRequiredIsRejected() {
            effective(requiredCollection(), requiredThirdParty());

            assertThatThrownBy(() -> service.record(command(
                    new Acceptance("COLLECTION_USE_ORDER", 1, true))))
                    .isInstanceOf(PrivacyConsentRequiredException.class)
                    .hasMessageContaining("THIRD_PARTY_DELIVERY");

            verify(saveConsentPort, never()).saveAll(any());
        }

        @Test @DisplayName("필수 항목을 거절해도 400 — 부분 저장을 남기지 않는다")
        void refusedRequiredIsRejectedWithoutPartialSave() {
            effective(requiredCollection(), requiredThirdParty());

            assertThatThrownBy(() -> service.record(command(
                    new Acceptance("COLLECTION_USE_ORDER", 1, true),
                    new Acceptance("THIRD_PARTY_DELIVERY", 1, false))))
                    .isInstanceOf(PrivacyConsentRequiredException.class);

            // 앞 항목만 저장되면 "동의는 받았는데 주문은 없는" 이력이 남는다.
            verify(saveConsentPort, never()).saveAll(any());
        }

        @Test @DisplayName("빠진 필수 코드를 전부 details 로 알려 준다")
        void reportsEveryMissingCode() {
            effective(requiredCollection(), requiredThirdParty());

            // 어떤 항목이 빠졌는지 안 알려 주면 클라이언트가 다시 보여 줄 대상을 모른다.
            assertThatThrownBy(() -> service.record(command()))
                    .asInstanceOf(InstanceOfAssertFactories.type(PrivacyConsentRequiredException.class))
                    .satisfies(error -> assertThat(error.getDetails())
                            .containsEntry("missingTermsCodes",
                                    List.of("COLLECTION_USE_ORDER", "THIRD_PARTY_DELIVERY")));
        }
    }

    @Nested
    @DisplayName("문안이 어긋날 때")
    class StaleTerms {

        @Test @DisplayName("버전이 다르면 409(PRIVACY_CONSENT_TERMS_STALE)")
        void versionMismatchIsConflict() {
            effective(terms("COLLECTION_USE_ORDER", 2, ConsentType.COLLECTION_USE, true, "hash-v2"));

            assertThatThrownBy(() -> service.record(command(
                    new Acceptance("COLLECTION_USE_ORDER", 1, true))))
                    .isInstanceOf(PrivacyConsentTermsStaleException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode())
                    .isEqualTo(ErrorCode.PRIVACY_CONSENT_TERMS_STALE);
        }

        @Test @DisplayName("버전이 비어 있어도 409 — 버전 없는 동의는 무엇에 대한 동의인지 모른다")
        void nullVersionIsConflict() {
            effective(requiredCollection());

            assertThatThrownBy(() -> service.record(command(
                    new Acceptance("COLLECTION_USE_ORDER", null, true))))
                    .isInstanceOf(PrivacyConsentTermsStaleException.class);
        }

        @Test @DisplayName("지금 유효하지 않은 코드가 오면 409 — 낡은 화면을 보고 있다는 뜻이다")
        void unknownCodeIsConflict() {
            effective(requiredCollection());

            assertThatThrownBy(() -> service.record(command(
                    new Acceptance("COLLECTION_USE_ORDER", 1, true),
                    new Acceptance("RETIRED_TERMS", 3, true))))
                    .isInstanceOf(PrivacyConsentTermsStaleException.class)
                    .hasMessageContaining("RETIRED_TERMS");
        }

        @Test @DisplayName("400 과 409 를 뭉개지 않는다 — 낡은 화면은 체크로 풀리지 않는다")
        void staleWinsOverMissingRequired() {
            // 필수 하나가 빠졌고 다른 하나는 버전이 낡았다. 이때 "체크하세요"(400)로 답하면
            // 사용자는 이미 체크한 항목을 다시 누르며 같은 화면을 맴돈다.
            effective(terms("COLLECTION_USE_ORDER", 2, ConsentType.COLLECTION_USE, true, "hash-v2"),
                    requiredThirdParty());

            assertThatThrownBy(() -> service.record(command(
                    new Acceptance("COLLECTION_USE_ORDER", 1, true))))
                    .isInstanceOf(PrivacyConsentTermsStaleException.class);
        }
    }

    @Nested
    @DisplayName("망가진 입력·설정")
    class BadInput {

        @Test @DisplayName("같은 문안이 두 번 오면 거절한다 — 어느 쪽이 뜻인지 알 수 없다")
        void duplicateCodeIsRejected() {
            effective(requiredCollection());

            // true 뒤에 false 를 붙여 보내는 조작이 "뒤엣것으로 덮기"에서 조용히 통한다.
            assertThatThrownBy(() -> service.record(command(
                    new Acceptance("COLLECTION_USE_ORDER", 1, true),
                    new Acceptance("COLLECTION_USE_ORDER", 1, false))))
                    .isInstanceOf(OrderInvariantViolationException.class)
                    .hasMessageContaining("두 번");
        }

        @Test @DisplayName("문안 코드가 비면 거절한다")
        void blankCodeIsRejected() {
            effective(requiredCollection());

            assertThatThrownBy(() -> service.record(command(new Acceptance("  ", 1, true))))
                    .isInstanceOf(OrderInvariantViolationException.class);
            assertThatThrownBy(() -> service.record(command(new Acceptance(null, 1, true))))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test @DisplayName("주문·동의자가 없으면 거절한다")
        void orderAndUserAreRequired() {
            assertThatThrownBy(() -> service.record(null))
                    .isInstanceOf(OrderInvariantViolationException.class);
            assertThatThrownBy(() -> service.record(new RecordCommand(null, 42L, List.of(), null)))
                    .isInstanceOf(OrderInvariantViolationException.class);
            assertThatThrownBy(() -> service.record(new RecordCommand(7L, null, List.of(), null)))
                    .isInstanceOf(OrderInvariantViolationException.class);
        }

        @Test @DisplayName("문안이 하나도 없으면 500 — 조용히 통과시키면 동의 없는 주문이 쌓인다")
        void emptyCatalogIsAServerFault() {
            when(loadTermsPort.findEffectiveAt(any())).thenReturn(List.of());

            assertThatThrownBy(() -> service.record(command()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getErrorCode())
                    .isEqualTo(ErrorCode.INTERNAL_ERROR);
        }
    }

    @Nested
    @DisplayName("문안 목록")
    class CurrentTerms {

        @Test @DisplayName("필수가 먼저, 그 안에서는 코드순 — 화면마다 다르게 보이지 않도록 여기서 정한다")
        void requiredFirstThenByCode() {
            effective(optionalMarketing(), requiredThirdParty(), requiredCollection());

            assertThat(service.currentTerms()).extracting(PrivacyConsentTerms::getCode)
                    .containsExactly("COLLECTION_USE_ORDER", "THIRD_PARTY_DELIVERY", "MARKETING_MESSAGE");
        }
    }

    @Nested
    @DisplayName("열람")
    class Viewing {

        private OrderPrivacyConsent stored(String code, int version, String bodySha256) {
            return terms(code, version, ConsentType.COLLECTION_USE, true, bodySha256)
                    .accept(7L, 42L, true, NOW, null);
        }

        @Test @DisplayName("본문이 그대로면 bodyUnchanged 가 참이다")
        void unchangedBody() {
            when(loadConsentPort.findByOrderId(7L))
                    .thenReturn(List.of(stored("COLLECTION_USE_ORDER", 1, "hash-v1")));
            when(loadTermsPort.findByCodeAndVersion("COLLECTION_USE_ORDER", 1))
                    .thenReturn(Optional.of(terms("COLLECTION_USE_ORDER", 1,
                            ConsentType.COLLECTION_USE, true, "hash-v1")));

            assertThat(service.ofOrder(7L)).singleElement()
                    .extracting(ConsentView::bodyUnchanged).isEqualTo(true);
        }

        @Test @DisplayName("버전을 안 올리고 문장을 고쳤으면 bodyUnchanged 가 거짓이다")
        void tamperedBody() {
            when(loadConsentPort.findByOrderId(7L))
                    .thenReturn(List.of(stored("COLLECTION_USE_ORDER", 1, "hash-v1")));
            when(loadTermsPort.findByCodeAndVersion("COLLECTION_USE_ORDER", 1))
                    .thenReturn(Optional.of(terms("COLLECTION_USE_ORDER", 1,
                            ConsentType.COLLECTION_USE, true, "hash-tampered")));

            assertThat(service.ofOrder(7L)).singleElement()
                    .extracting(ConsentView::bodyUnchanged).isEqualTo(false);
        }

        @Test @DisplayName("문안 행이 사라졌으면 \"같다\"고 말하지 않는다")
        void missingTermsIsNotUnchanged() {
            when(loadConsentPort.findByOrderId(7L))
                    .thenReturn(List.of(stored("COLLECTION_USE_ORDER", 1, "hash-v1")));
            when(loadTermsPort.findByCodeAndVersion(anyString(), anyInt())).thenReturn(Optional.empty());

            assertThat(service.ofOrder(7L)).singleElement()
                    .extracting(ConsentView::bodyUnchanged).isEqualTo(false);
        }

        @Test @DisplayName("같은 (코드, 버전) 은 한 번만 읽는다 — 목록이 길어져도 질의가 늘지 않는다")
        void cachesTermsLookup() {
            when(loadConsentPort.findByUserId(eq(42L), anyInt())).thenReturn(List.of(
                    stored("COLLECTION_USE_ORDER", 1, "hash-v1"),
                    stored("COLLECTION_USE_ORDER", 1, "hash-v1"),
                    stored("COLLECTION_USE_ORDER", 1, "hash-v1")));
            when(loadTermsPort.findByCodeAndVersion("COLLECTION_USE_ORDER", 1))
                    .thenReturn(Optional.of(terms("COLLECTION_USE_ORDER", 1,
                            ConsentType.COLLECTION_USE, true, "hash-v1")));

            assertThat(service.ofUser(42L, 100)).hasSize(3);
            verify(loadTermsPort, times(1)).findByCodeAndVersion("COLLECTION_USE_ORDER", 1);
        }

        @Test @DisplayName("limit 은 1~500 으로 잘린다 — 호출자가 부른 값이 그대로 질의가 되지 않는다")
        void limitIsClamped() {
            when(loadConsentPort.findByUserId(eq(42L), anyInt())).thenReturn(List.of());
            when(loadConsentPort.findByTermsCodeAndVersion(anyString(), anyInt(), anyInt()))
                    .thenReturn(List.of());

            service.ofUser(42L, 10_000);
            verify(loadConsentPort).findByUserId(42L, 500);

            service.ofUser(42L, 0);
            verify(loadConsentPort).findByUserId(42L, 1);

            service.ofTermsVersion("COLLECTION_USE_ORDER", 1, -5);
            verify(loadConsentPort).findByTermsCodeAndVersion("COLLECTION_USE_ORDER", 1, 1);
        }

        @Test @DisplayName("빈 조건에는 질의하지 않고 빈 목록을 준다")
        void blankQueryShortCircuits() {
            assertThat(service.ofOrder(null)).isEmpty();
            assertThat(service.ofUser(null, 10)).isEmpty();
            assertThat(service.ofTermsVersion(null, 1, 10)).isEmpty();
            assertThat(service.ofTermsVersion("  ", 1, 10)).isEmpty();
            assertThat(service.ofTermsVersion("CODE", 0, 10)).isEmpty();

            verify(loadConsentPort, never()).findByOrderId(any());
            verify(loadConsentPort, never()).findByUserId(any(), anyInt());
            verify(loadConsentPort, never()).findByTermsCodeAndVersion(anyString(), anyInt(), anyInt());
        }
    }

    @Nested
    @DisplayName("저장 호출")
    class Saving {

        @Test @DisplayName("한 번에 모아 저장한다 — 줄마다 부르면 실패 지점이 흩어진다")
        void savesInOneCall() {
            effective(requiredCollection(), optionalMarketing());

            service.record(command(
                    new Acceptance("COLLECTION_USE_ORDER", 1, true),
                    new Acceptance("MARKETING_MESSAGE", 1, true)));

            assertThat(captureSaved()).hasSize(2);
            verify(saveConsentPort, times(1)).saveAll(any());
        }
    }

    @Test @DisplayName("동의 시각은 주입된 Clock 이 정한다 — 클라이언트가 보낸 시각이 아니다")
    void agreedAtComesFromClock() {
        effective(requiredCollection());

        List<OrderPrivacyConsent> saved = service.record(command(
                new Acceptance("COLLECTION_USE_ORDER", 1, true)));

        assertThat(saved.getFirst().getAgreedAt()).isEqualTo(NOW);
    }
}
