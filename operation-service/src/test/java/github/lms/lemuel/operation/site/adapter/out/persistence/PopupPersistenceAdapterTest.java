package github.lms.lemuel.operation.site.adapter.out.persistence;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.site.domain.Popup;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 팝업 매핑 — 도메인 규칙이 아니라 <b>왕복과 순서</b>를 본다.
 *
 * <p>정렬을 확인하는 이유: 순서가 보장되지 않으면 운영자가 정한 노출 순서가 새로고침마다 달라진다.
 * 이건 오류가 아니라 "이상한데?"로만 보이는 종류라 테스트가 없으면 영영 안 잡힌다.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.datasource.url=jdbc:h2:mem:popupmapping;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;"
                        + "INIT=CREATE SCHEMA IF NOT EXISTS site",
                "spring.jpa.properties.hibernate.hbm2ddl.create_namespaces=true",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
@Transactional
class PopupPersistenceAdapterTest {

    private static final Instant NOW = Instant.parse("2026-09-15T00:00:00Z");

    @Autowired private PopupPersistenceAdapter popups;

    private Popup save(String title, int sortOrder, Instant startsAt, Instant endsAt) {
        return popups.save(Popup.register(UUID.randomUUID(), title, "https://cdn/" + title + ".png",
                "https://site/notice", true, startsAt, endsAt, sortOrder, "admin"));
    }

    private Popup window(String title, int sortOrder) {
        return save(title, sortOrder, NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("팝업은 왕복해도 그대로다")
    void roundTrip() {
        Popup saved = window("추석 휴진", 2);

        Popup found = popups.findById(saved.id()).orElseThrow();

        assertThat(found.title()).isEqualTo("추석 휴진");
        assertThat(found.imageUrl()).isEqualTo("https://cdn/추석 휴진.png");
        assertThat(found.linkUrl()).isEqualTo("https://site/notice");
        assertThat(found.openInNewWindow()).isTrue();
        assertThat(found.startsAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(found.endsAt()).isEqualTo(NOW.plus(Duration.ofDays(1)));
        assertThat(found.sortOrder()).isEqualTo(2);
        assertThat(found.active()).isTrue();
        assertThat(found.deleted()).isFalse();
    }

    @Test
    @DisplayName("관리 목록은 노출 순서대로 나온다 — 등록 순서가 아니다")
    void listIsOrderedBySortOrder() {
        window("셋째", 3);
        window("첫째", 1);
        window("둘째", 2);

        assertThat(popups.findAll()).extracting(Popup::title)
                .containsExactly("첫째", "둘째", "셋째");
    }

    @Test
    @DisplayName("지운 팝업은 관리 목록에서도 노출 목록에서도 빠진다")
    void deletedIsExcludedEverywhere() {
        Popup keep = window("남길 것", 1);
        Popup drop = window("지울 것", 2);

        Popup deleted = drop;
        deleted.delete("admin");
        popups.save(deleted);

        assertThat(popups.findAll()).extracting(Popup::id).containsExactly(keep.id());
        assertThat(popups.findVisibleAt(NOW)).extracting(Popup::id).containsExactly(keep.id());
        // 행 자체는 남는다 — 되짚을 수 있어야 한다.
        assertThat(popups.findById(drop.id())).isPresent();
    }

    @Test
    @DisplayName("꺼 둔 팝업은 관리 목록엔 남고 노출 목록에서만 빠진다")
    void deactivatedStaysInTheAdminList() {
        Popup on = window("켜 둔 것", 1);
        Popup off = window("꺼 둔 것", 2);

        off.deactivate("admin");
        popups.save(off);

        assertThat(popups.findAll()).extracting(Popup::title)
                .containsExactly("켜 둔 것", "꺼 둔 것");
        assertThat(popups.findVisibleAt(NOW)).extracting(Popup::id).containsExactly(on.id());
    }

    @Test
    @DisplayName("예약과 종료는 노출 목록에 없다 — 시작 시각을 실제로 본다")
    void windowIsHonoredOnBothEnds() {
        Popup now = window("지금", 1);
        save("예약", 2, NOW.plus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(2)));
        save("종료", 3, NOW.minus(Duration.ofDays(2)), NOW.minus(Duration.ofDays(1)));

        List<Popup> visible = popups.findVisibleAt(NOW);

        assertThat(visible).extracting(Popup::id).containsExactly(now.id());
        // 관리 목록에는 셋 다 있다 — 예약과 종료도 운영자가 봐야 하는 대상이다.
        assertThat(popups.findAll()).hasSize(3);
    }

    @Test
    @DisplayName("노출 목록도 순서를 지킨다 — 화면에 뜨는 차례가 그 순서다")
    void visibleIsOrderedToo() {
        window("셋째", 3);
        window("첫째", 1);
        window("둘째", 2);

        assertThat(popups.findVisibleAt(NOW)).extracting(Popup::title)
                .containsExactly("첫째", "둘째", "셋째");
    }

    @Test
    @DisplayName("같은 식별자로 다시 저장하면 행이 늘지 않고 갱신된다")
    void saveUpdatesInPlace() {
        Popup saved = window("원래 제목", 1);

        saved.update("바뀐 제목", null, null, false,
                NOW.minus(Duration.ofDays(3)), NOW.plus(Duration.ofDays(3)), 9, "editor");
        popups.save(saved);

        assertThat(popups.findAll()).hasSize(1);
        Popup found = popups.findById(saved.id()).orElseThrow();
        assertThat(found.title()).isEqualTo("바뀐 제목");
        assertThat(found.imageUrl()).isNull();
        assertThat(found.openInNewWindow()).isFalse();
        assertThat(found.sortOrder()).isEqualTo(9);
        assertThat(found.updatedBy()).isEqualTo("editor");
    }

    @Test
    @DisplayName("없는 식별자는 빈 값이다")
    void missingIsEmpty() {
        assertThat(popups.findById(UUID.randomUUID())).isEmpty();
    }
}
