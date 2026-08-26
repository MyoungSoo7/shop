package github.lms.lemuel.order.adapter.out.persistence;

import github.lms.lemuel.order.domain.ConsentType;
import github.lms.lemuel.order.domain.OrderPrivacyConsent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 동의 이력 영속화 어댑터.
 *
 * <p>이 어댑터가 스스로 하는 일은 세 가지뿐이라 그 셋만 본다 — <b>도메인↔엔티티 매핑</b>,
 * <b>없는 조건에는 질의하지 않기</b>, <b>limit 을 페이지 크기로 옮기며 상한 걸기</b>.
 *
 * <p>세 번째가 특히 중요하다. {@code PageRequest.of(0, limit)} 는 limit 이 0 이하면 그 자리에서
 * 터지고, 상한이 없으면 호출자가 준 수가 그대로 질의가 된다. 서비스에도 같은 상한이 있지만
 * 어댑터를 서비스 없이 부르는 경로(테스트·배치)가 언제든 생기므로 두 자리 모두 건다.
 */
@DisplayName("PrivacyConsentPersistenceAdapter — 주문 시점 동의 영속화")
class PrivacyConsentPersistenceAdapterTest {

    private static final LocalDateTime AGREED_AT = LocalDateTime.of(2026, 8, 27, 10, 0);

    private SpringDataPrivacyConsentTermsRepository termsRepository;
    private SpringDataOrderPrivacyConsentRepository consentRepository;
    private PrivacyConsentPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        termsRepository = mock(SpringDataPrivacyConsentTermsRepository.class);
        consentRepository = mock(SpringDataOrderPrivacyConsentRepository.class);
        adapter = new PrivacyConsentPersistenceAdapter(termsRepository, consentRepository);
    }

    private static OrderPrivacyConsent consent() {
        return OrderPrivacyConsent.restore(5L, 7L, 42L, "THIRD_PARTY_DELIVERY", 2,
                ConsentType.THIRD_PARTY_PROVISION, true, "배송업체", "주문 상품의 배송",
                "받는 분 이름, 휴대전화번호, 주소", "배송 완료 후 90일", "hash-v2",
                AGREED_AT, "203.0.113.7", AGREED_AT);
    }

    private Pageable capturePageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(consentRepository).findByUserIdOrderByAgreedAtDesc(anyLong(), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("매핑")
    class Mapping {

        @Test @DisplayName("도메인 → 엔티티 → 도메인 왕복에서 칸이 하나도 새지 않는다")
        void roundTrip() {
            OrderPrivacyConsent origin = consent();

            OrderPrivacyConsent back = OrderPrivacyConsentJpaEntity.fromDomain(origin).toDomain();

            assertThat(back).usingRecursiveComparison().isEqualTo(origin);
        }

        @Test @DisplayName("선택 칸이 비어 있어도 왕복한다")
        void roundTripWithNulls() {
            // 제3자 제공이 아니면 recipient 가 없고, 프록시를 못 읽으면 IP 도 없다.
            OrderPrivacyConsent origin = OrderPrivacyConsent.restore(6L, 7L, 42L,
                    "MARKETING_MESSAGE", 1, ConsentType.MARKETING, false, null, "광고 발송",
                    "휴대전화번호", "동의 철회 시까지", "hash-m1", AGREED_AT, null, AGREED_AT);

            OrderPrivacyConsent back = OrderPrivacyConsentJpaEntity.fromDomain(origin).toDomain();

            assertThat(back).usingRecursiveComparison().isEqualTo(origin);
        }

        @Test @DisplayName("저장한 결과를 도메인으로 되돌려 준다")
        void saveAllMapsBack() {
            when(consentRepository.saveAll(any())).thenAnswer(call -> call.getArgument(0));

            List<OrderPrivacyConsent> saved = adapter.saveAll(List.of(consent()));

            assertThat(saved).singleElement()
                    .usingRecursiveComparison().isEqualTo(consent());
        }
    }

    @Nested
    @DisplayName("없는 조건에는 질의하지 않는다")
    class ShortCircuits {

        @Test @DisplayName("저장할 것이 없으면 저장소를 부르지 않는다")
        void emptySaveDoesNotTouchRepository() {
            assertThat(adapter.saveAll(List.of())).isEmpty();
            assertThat(adapter.saveAll(null)).isEmpty();

            verifyNoInteractions(consentRepository);
        }

        @Test @DisplayName("조회 키가 비면 빈 결과 — 전체 스캔으로 번지지 않는다")
        void blankKeysReturnEmpty() {
            assertThat(adapter.findEffectiveAt(null)).isEmpty();
            assertThat(adapter.findByCodeAndVersion(null, 1)).isEmpty();
            assertThat(adapter.findByCodeAndVersion("  ", 1)).isEmpty();
            assertThat(adapter.findByCodeAndVersion("CODE", 0)).isEmpty();
            assertThat(adapter.findByOrderId(null)).isEmpty();
            assertThat(adapter.findByUserId(null, 10)).isEmpty();
            assertThat(adapter.findByTermsCodeAndVersion(null, 1, 10)).isEmpty();
            assertThat(adapter.findByTermsCodeAndVersion("  ", 1, 10)).isEmpty();
            assertThat(adapter.findByTermsCodeAndVersion("CODE", 0, 10)).isEmpty();

            verifyNoInteractions(termsRepository, consentRepository);
        }

        @Test @DisplayName("문안이 없으면 빈 Optional")
        void missingTermsIsEmptyOptional() {
            when(termsRepository.findByCodeAndVersion(anyString(), anyInt())).thenReturn(Optional.empty());

            assertThat(adapter.findByCodeAndVersion("THIRD_PARTY_DELIVERY", 2)).isEmpty();
        }
    }

    @Nested
    @DisplayName("limit")
    class Limits {

        @Test @DisplayName("첫 페이지를 요청한 크기로 읽는다")
        void limitBecomesPageSize() {
            when(consentRepository.findByUserIdOrderByAgreedAtDesc(anyLong(), any())).thenReturn(List.of());

            adapter.findByUserId(42L, 20);

            Pageable pageable = capturePageable();
            assertThat(pageable.getPageNumber()).isZero();
            assertThat(pageable.getPageSize()).isEqualTo(20);
        }

        @Test @DisplayName("상한 500 을 넘겨 부르면 500 으로 잘린다")
        void limitIsCappedAtFiveHundred() {
            when(consentRepository.findByUserIdOrderByAgreedAtDesc(anyLong(), any())).thenReturn(List.of());

            adapter.findByUserId(42L, 10_000);

            assertThat(capturePageable().getPageSize()).isEqualTo(500);
        }

        @Test @DisplayName("0 이하로 부르면 1 이 된다 — PageRequest 가 그 자리에서 터지기 때문이다")
        void nonPositiveLimitBecomesOne() {
            when(consentRepository.findByUserIdOrderByAgreedAtDesc(anyLong(), any())).thenReturn(List.of());

            adapter.findByUserId(42L, 0);

            assertThat(capturePageable().getPageSize()).isEqualTo(1);
        }

        @Test @DisplayName("문안 버전 축에도 같은 상한이 걸린다")
        void termsVersionAxisIsCappedToo() {
            when(consentRepository.findByTermsCodeAndTermsVersionOrderByAgreedAtDesc(
                    anyString(), anyInt(), any())).thenReturn(List.of());

            adapter.findByTermsCodeAndVersion("THIRD_PARTY_DELIVERY", 2, 10_000);

            ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
            verify(consentRepository).findByTermsCodeAndTermsVersionOrderByAgreedAtDesc(
                    eq("THIRD_PARTY_DELIVERY"), eq(2), captor.capture());
            assertThat(captor.getValue().getPageSize()).isEqualTo(500);
        }

        @Test @DisplayName("주문별 조회에는 페이지가 없다 — 한 주문의 동의는 몇 건뿐이라 자르면 사실이 빠진다")
        void byOrderIsNotPaged() {
            when(consentRepository.findByOrderIdOrderByTermsCodeAsc(7L)).thenReturn(List.of());

            adapter.findByOrderId(7L);

            verify(consentRepository).findByOrderIdOrderByTermsCodeAsc(7L);
            verify(consentRepository, never()).findByUserIdOrderByAgreedAtDesc(anyLong(), any());
        }
    }
}
