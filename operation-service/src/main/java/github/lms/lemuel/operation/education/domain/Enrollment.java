package github.lms.lemuel.operation.education.domain;

import github.lms.lemuel.operation.education.domain.exception.InvalidEnrollmentStateException;

import java.time.Instant;
import java.util.UUID;

/**
 * 수강 신청 애그리거트 — WAITING → CONFIRMED → CANCELLED 상태머신의 단일 진실원.
 *
 * <p><b>확정은 자동이 아니다.</b> 신청은 언제나 WAITING 으로 들어오고 사람이 {@link #confirm}
 * 해야 자리가 된다. 취소가 나면 뒤 순번을 자동 승격시키고 싶은 유혹이 있지만, 승격은 그 사람에게
 * "당신은 이 날 이 자리에 온다"를 확정짓는 행위다 — 아무도 누르지 않은 확정은 안내도 못 나간다.
 *
 * <p><b>취소는 되돌리지 않는다.</b> CANCELLED 에서 CONFIRMED 로 되돌리는 경로를 열면, 신청자가
 * 포기한 자리를 본인 동의 없이 되살리게 된다. 다시 오겠다면 새 신청을 만든다 — DB 의 부분 유니크
 * 인덱스가 CANCELLED 를 제외하는 이유가 이것이다.
 *
 * <p>정원 검사는 여기 없다. 정원은 과정의 성질이라 {@link Course#changeCapacity} 와
 * {@link Course#ensureSeatAvailable} 가 소유한다 — 신청 한 건은 다른 신청이 몇 건인지 모른다.
 */
public final class Enrollment {
    private final UUID id;
    private final UUID courseId;
    private final String applicantId;
    private String applicantName;
    private String applicantOrganization;
    private EnrollmentStatus status;
    private String adminMemo;
    private String cancelReason;
    private final Instant appliedAt;
    private Instant confirmedAt;
    private Instant cancelledAt;
    private String updatedBy;
    private final long version;

    private Enrollment(UUID id, UUID courseId, String applicantId, String applicantName,
                       String applicantOrganization, EnrollmentStatus status, String adminMemo,
                       String cancelReason, Instant appliedAt, Instant confirmedAt, Instant cancelledAt,
                       String updatedBy, long version) {
        if (courseId == null) throw new IllegalArgumentException("courseId is required");
        if (applicantId == null || applicantId.isBlank()) throw new IllegalArgumentException("applicantId is required");
        if (applicantName == null || applicantName.isBlank()) throw new IllegalArgumentException("applicantName is required");
        this.id = id;
        this.courseId = courseId;
        this.applicantId = applicantId;
        this.applicantName = applicantName;
        this.applicantOrganization = applicantOrganization;
        this.status = status;
        this.adminMemo = adminMemo;
        this.cancelReason = cancelReason;
        this.appliedAt = appliedAt;
        this.confirmedAt = confirmedAt;
        this.cancelledAt = cancelledAt;
        this.updatedBy = updatedBy;
        this.version = version;
    }

    public static Enrollment apply(UUID id, UUID courseId, String applicantId, String applicantName,
                                   String applicantOrganization, String actor) {
        return new Enrollment(id, courseId, applicantId, applicantName, applicantOrganization,
                EnrollmentStatus.WAITING, null, null, Instant.now(), null, null, actor, 0L);
    }

    /** 영속 상태에서 애그리거트를 되살린다 — 어댑터 전용 진입점(상태 규칙을 우회하지 않는다). */
    public static Enrollment rehydrate(UUID id, UUID courseId, String applicantId, String applicantName,
                                       String applicantOrganization, EnrollmentStatus status, String adminMemo,
                                       String cancelReason, Instant appliedAt, Instant confirmedAt,
                                       Instant cancelledAt, String updatedBy, long version) {
        return new Enrollment(id, courseId, applicantId, applicantName, applicantOrganization, status,
                adminMemo, cancelReason, appliedAt, confirmedAt, cancelledAt, updatedBy, version);
    }

    public void confirm(String actor) {
        require(EnrollmentStatus.WAITING);
        status = EnrollmentStatus.CONFIRMED;
        confirmedAt = Instant.now();
        updatedBy = actor;
    }

    /**
     * 취소한다. 사유는 필수다 — 나중에 정원을 다시 세거나 분쟁이 났을 때 "왜 이 자리가 비었는가"에
     * 답할 수 있는 유일한 기록이고, 비워 두면 운영자의 취소와 본인 취소가 구분되지 않는다.
     */
    public void cancel(String reason, String actor) {
        require(EnrollmentStatus.WAITING, EnrollmentStatus.CONFIRMED);
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("cancel reason is required");
        status = EnrollmentStatus.CANCELLED;
        cancelReason = reason;
        cancelledAt = Instant.now();
        updatedBy = actor;
    }

    /** 신청자 정보 정정 — 동명이인·소속 오기입을 고치는 경로다. 취소분은 더 이상 손대지 않는다. */
    public void correct(String applicantName, String applicantOrganization, String actor) {
        require(EnrollmentStatus.WAITING, EnrollmentStatus.CONFIRMED);
        if (applicantName == null || applicantName.isBlank()) throw new IllegalArgumentException("applicantName is required");
        this.applicantName = applicantName;
        this.applicantOrganization = applicantOrganization;
        this.updatedBy = actor;
    }

    /** 운영 메모는 취소된 신청에도 남길 수 있다 — 취소 경위를 뒤에 덧붙이는 것이 정상 경로다. */
    public void memo(String memo, String actor) {
        this.adminMemo = memo;
        this.updatedBy = actor;
    }

    /** 이 신청이 정원 한 자리를 차지하고 있는가. 대기는 아직 자리가 아니다. */
    public boolean occupiesSeat() { return status == EnrollmentStatus.CONFIRMED; }

    private void require(EnrollmentStatus... allowed) {
        for (EnrollmentStatus candidate : allowed) if (status == candidate) return;
        throw new InvalidEnrollmentStateException("enrollment cannot transition from " + status);
    }

    public UUID id() { return id; }
    public UUID courseId() { return courseId; }
    public String applicantId() { return applicantId; }
    public String applicantName() { return applicantName; }
    public String applicantOrganization() { return applicantOrganization; }
    public EnrollmentStatus status() { return status; }
    public String adminMemo() { return adminMemo; }
    public String cancelReason() { return cancelReason; }
    public Instant appliedAt() { return appliedAt; }
    public Instant confirmedAt() { return confirmedAt; }
    public Instant cancelledAt() { return cancelledAt; }
    public String updatedBy() { return updatedBy; }
    public long version() { return version; }
}
