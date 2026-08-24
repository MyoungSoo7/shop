package github.lms.lemuel.category.application.service;

import github.lms.lemuel.category.application.port.out.DisplaySectionPort;
import github.lms.lemuel.category.domain.DisplaySection;
import github.lms.lemuel.category.domain.DisplaySectionItem;
import github.lms.lemuel.category.domain.DisplaySectionKind;
import github.lms.lemuel.category.domain.exception.CategoryNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DisplaySectionServiceTest {

    /** 2026-07-15 12:00 — 아래 편성들의 기간이 이 시각을 기준으로 안/밖으로 갈린다. */
    private static final ZoneId ZONE = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-07-15T03:00:00Z"), ZONE);

    @Mock DisplaySectionPort port;

    private DisplaySectionService service() {
        return new DisplaySectionService(port, CLOCK);
    }

    private DisplaySection section(Long id, String code, boolean active,
                                   LocalDateTime startsAt, LocalDateTime endsAt) {
        return DisplaySection.rehydrate(id, code, "기획전 " + code, DisplaySectionKind.EXHIBITION,
                null, startsAt, endsAt, 0, active);
    }

    private DisplaySection summer() {
        return section(7L, "EXH_2026_SUMMER", true,
                LocalDateTime.of(2026, 6, 1, 0, 0), LocalDateTime.of(2026, 8, 31, 23, 59));
    }

    @Test
    @DisplayName("노출 목록은 기간 밖·비활성 편성을 뺀다 — 판정은 저장된 플래그가 아니라 시각 계산이다")
    void visibleSectionsFilterByClock() {
        DisplaySection ended = section(8L, "EXH_2026_SPRING", true,
                LocalDateTime.of(2026, 3, 1, 0, 0), LocalDateTime.of(2026, 5, 31, 23, 59));
        DisplaySection paused = section(9L, "EXH_PAUSED", false, null, null);
        when(port.loadAll()).thenReturn(List.of(summer(), ended, paused));

        assertThat(service().getVisibleSections())
                .extracting(DisplaySection::getCode)
                .containsExactly("EXH_2026_SUMMER");
    }

    @Test
    @DisplayName("운영 조회는 노출이 끝난 편성의 내용도 준다 — 편성을 짜는 자리다")
    void adminItemsIgnoreVisibility() {
        DisplaySection ended = section(8L, "EXH_2026_SPRING", true,
                LocalDateTime.of(2026, 3, 1, 0, 0), LocalDateTime.of(2026, 5, 31, 23, 59));
        when(port.findByCode("EXH_2026_SPRING")).thenReturn(Optional.of(ended));
        when(port.loadItems(8L)).thenReturn(List.of(DisplaySectionItem.of(8L, 101L, 0, false)));

        assertThat(service().getItems("EXH_2026_SPRING"))
                .extracting(DisplaySectionItem::getProductId)
                .containsExactly(101L);
    }

    @Test
    @DisplayName("공개 조회는 노출 중인 편성의 내용만 준다")
    void publicItemsOfVisibleSection() {
        when(port.findByCode("EXH_2026_SUMMER")).thenReturn(Optional.of(summer()));
        when(port.loadItems(7L)).thenReturn(List.of(DisplaySectionItem.of(7L, 101L, 0, true)));

        assertThat(service().getVisibleItems("EXH_2026_SUMMER"))
                .extracting(DisplaySectionItem::getProductId)
                .containsExactly(101L);
    }

    @Test
    @DisplayName("아직 시작 안 한 편성의 라인업은 공개 조회로 새지 않는다 — 코드만 알면 미공개 기획전을 미리 보는 구멍")
    void publicItemsHideNotStartedSection() {
        DisplaySection upcoming = section(10L, "EXH_2026_FALL", true,
                LocalDateTime.of(2026, 9, 1, 0, 0), null);
        when(port.findByCode("EXH_2026_FALL")).thenReturn(Optional.of(upcoming));

        assertThatThrownBy(() -> service().getVisibleItems("EXH_2026_FALL"))
                .isInstanceOf(CategoryNotFoundException.class);

        // 존재 여부까지 흘리지 않는다 — 항목을 읽으러 가지도 않는다
        verify(port, never()).loadItems(10L);
    }

    @Test
    @DisplayName("끝났거나 내려둔 편성의 라인업도 공개 조회로는 보이지 않는다")
    void publicItemsHideEndedAndInactiveSection() {
        DisplaySection ended = section(8L, "EXH_2026_SPRING", true,
                LocalDateTime.of(2026, 3, 1, 0, 0), LocalDateTime.of(2026, 5, 31, 23, 59));
        DisplaySection paused = section(9L, "EXH_PAUSED", false, null, null);
        when(port.findByCode("EXH_2026_SPRING")).thenReturn(Optional.of(ended));
        when(port.findByCode("EXH_PAUSED")).thenReturn(Optional.of(paused));

        DisplaySectionService service = service();
        assertThatThrownBy(() -> service.getVisibleItems("EXH_2026_SPRING"))
                .isInstanceOf(CategoryNotFoundException.class);
        assertThatThrownBy(() -> service.getVisibleItems("EXH_PAUSED"))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    @DisplayName("없는 편성 코드는 공개·운영 양쪽에서 같은 404 다")
    void unknownCode() {
        when(port.findByCode("NOPE")).thenReturn(Optional.empty());

        DisplaySectionService service = service();
        assertThatThrownBy(() -> service.getVisibleItems("NOPE"))
                .isInstanceOf(CategoryNotFoundException.class);
        assertThatThrownBy(() -> service.getItems("NOPE"))
                .isInstanceOf(CategoryNotFoundException.class);
    }
}
