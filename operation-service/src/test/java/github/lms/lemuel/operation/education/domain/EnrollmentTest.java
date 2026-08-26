package github.lms.lemuel.operation.education.domain;

import github.lms.lemuel.operation.education.domain.exception.InvalidEnrollmentStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 수강 신청 애그리거트 — 자리를 <b>주고 거두는</b> 규칙만 검증한다.
 *
 * <p>검증의 초점은 "상태가 바뀌는가"가 아니라 <b>바뀌면 안 되는 경로가 막히는가</b>다. 취소를
 * 되돌리는 경로와 사유 없는 취소가 그것이고, 둘 다 뚫리면 기록만으로는 무슨 일이 있었는지
 * 재구성할 수 없게 된다.
 */
class EnrollmentTest {

    private static Enrollment applied() {
        return Enrollment.apply(UUID.randomUUID(), UUID.randomUUID(), "u-1", "김운영", "OO치과", "admin");
    }

    @Test
    @DisplayName("신청은 언제나 대기로 들어온다 — 자동 확정이 없다")
    void appliesAsWaiting() {
        Enrollment enrollment = applied();

        assertThat(enrollment.status()).isEqualTo(EnrollmentStatus.WAITING);
        assertThat(enrollment.confirmedAt()).isNull();
        // 대기는 아직 자리가 아니다 — 정원을 셀 때 포함되면 대기자가 정원을 잡아먹는다.
        assertThat(enrollment.occupiesSeat()).isFalse();
    }

    @Test
    @DisplayName("확정하면 자리를 차지하고 확정 시각이 남는다")
    void confirmTakesSeat() {
        Enrollment enrollment = applied();

        enrollment.confirm("admin");

        assertThat(enrollment.status()).isEqualTo(EnrollmentStatus.CONFIRMED);
        assertThat(enrollment.occupiesSeat()).isTrue();
        assertThat(enrollment.confirmedAt()).isNotNull();
    }

    @Test
    @DisplayName("이미 확정된 신청을 또 확정하지 않는다")
    void doubleConfirmIsRejected() {
        Enrollment enrollment = applied();
        enrollment.confirm("admin");

        assertThatThrownBy(() -> enrollment.confirm("admin"))
                .isInstanceOf(InvalidEnrollmentStateException.class);
    }

    @Test
    @DisplayName("취소는 되돌릴 수 없다 — 본인이 놓은 자리를 동의 없이 되살리지 않는다")
    void cancelledEnrollmentCannotBeConfirmedAgain() {
        Enrollment enrollment = applied();
        enrollment.confirm("admin");
        enrollment.cancel("본인 요청", "admin");

        assertThatThrownBy(() -> enrollment.confirm("admin"))
                .isInstanceOf(InvalidEnrollmentStateException.class);
        assertThatThrownBy(() -> enrollment.cancel("또 취소", "admin"))
                .isInstanceOf(InvalidEnrollmentStateException.class);
        assertThat(enrollment.occupiesSeat()).isFalse();
    }

    @Test
    @DisplayName("사유 없는 취소는 받지 않는다 — 운영자 취소와 본인 취소가 구분되지 않는다")
    void cancelRequiresReason() {
        Enrollment enrollment = applied();

        assertThatThrownBy(() -> enrollment.cancel("  ", "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        // 거절됐으면 상태도 그대로여야 한다 — 반쯤 취소된 신청이 남으면 안 된다.
        assertThat(enrollment.status()).isEqualTo(EnrollmentStatus.WAITING);
        assertThat(enrollment.cancelReason()).isNull();
    }

    @Test
    @DisplayName("신청자 정정은 살아 있는 신청에만 — 취소분은 더 손대지 않는다")
    void correctOnlyWhileAlive() {
        Enrollment enrollment = applied();
        enrollment.correct("김정정", "△△치과", "admin");
        assertThat(enrollment.applicantName()).isEqualTo("김정정");
        assertThat(enrollment.applicantOrganization()).isEqualTo("△△치과");

        enrollment.cancel("본인 요청", "admin");

        assertThatThrownBy(() -> enrollment.correct("또정정", null, "admin"))
                .isInstanceOf(InvalidEnrollmentStateException.class);
    }

    @Test
    @DisplayName("운영 메모는 취소된 신청에도 남는다 — 취소 경위를 뒤에 덧붙이는 게 정상 경로다")
    void memoWorksAfterCancel() {
        Enrollment enrollment = applied();
        enrollment.cancel("연락 두절", "admin");

        enrollment.memo("3회 연락 시도함", "admin");

        assertThat(enrollment.adminMemo()).isEqualTo("3회 연락 시도함");
    }

    @Test
    @DisplayName("되살린 애그리거트도 전이 규칙을 그대로 받는다 — 우회 경로가 아니다")
    void rehydrateKeepsRules() {
        Enrollment enrollment = Enrollment.rehydrate(UUID.randomUUID(), UUID.randomUUID(), "u-1", "김운영",
                null, EnrollmentStatus.CANCELLED, null, "본인 요청", Instant.now(), null, Instant.now(), "admin", 3L);

        assertThat(enrollment.version()).isEqualTo(3L);
        assertThatThrownBy(() -> enrollment.confirm("admin"))
                .isInstanceOf(InvalidEnrollmentStateException.class);
    }

    @Test
    @DisplayName("신청자 식별자와 이름은 필수다")
    void applicantIsRequired() {
        UUID courseId = UUID.randomUUID();
        assertThatThrownBy(() -> Enrollment.apply(UUID.randomUUID(), courseId, " ", "김운영", null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Enrollment.apply(UUID.randomUUID(), courseId, "u-1", " ", null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Enrollment.apply(UUID.randomUUID(), null, "u-1", "김운영", null, "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
