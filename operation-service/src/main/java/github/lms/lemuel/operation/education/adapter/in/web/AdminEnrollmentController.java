package github.lms.lemuel.operation.education.adapter.in.web;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.application.port.in.CapacitySummary;
import github.lms.lemuel.operation.education.application.port.in.ManageEnrollmentUseCase;
import github.lms.lemuel.operation.education.application.port.in.QueryEnrollmentUseCase;
import github.lms.lemuel.operation.education.domain.Enrollment;
import github.lms.lemuel.operation.education.domain.EnrollmentStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.UUID;

/**
 * 수강 신청 콘솔 — {@code /admin/education/enrollments}.
 *
 * <p>경로가 {@code /admin/education/**} 아래인 덕에 {@code EducationSecurityConfig}(@Order(4)) 의
 * ADMIN 게이팅이 그대로 적용된다. 새 필터체인을 만들면 같은 규칙이 두 곳에 생기고 둘이 어긋난다.
 */
@RestController
@RequestMapping("/admin/education/enrollments")
public class AdminEnrollmentController {

    private final QueryEnrollmentUseCase queryEnrollment;
    private final ManageEnrollmentUseCase manageEnrollment;

    public AdminEnrollmentController(QueryEnrollmentUseCase queryEnrollment,
                                     ManageEnrollmentUseCase manageEnrollment) {
        this.queryEnrollment = queryEnrollment;
        this.manageEnrollment = manageEnrollment;
    }

    @GetMapping
    public Page<EnrollmentResponse> list(@RequestParam(required = false) UUID courseId,
                                         @RequestParam(required = false) EnrollmentStatus status,
                                         @RequestParam(defaultValue = "") String keyword,
                                         Pageable pageable) {
        PageSlice<Enrollment> slice = queryEnrollment.list(courseId, status, keyword,
                new PageSpec(pageable.getPageNumber(), pageable.getPageSize()));
        // 응답 JSON 모양(content/totalElements/totalPages/number/size)을 유지하려고 여기서만 Page 로 되싼다.
        return new PageImpl<>(slice.content().stream().map(EnrollmentResponse::from).toList(),
                PageRequest.of(slice.page(), slice.size()), slice.totalElements());
    }

    @GetMapping("/summary")
    public SummaryResponse summary(@RequestParam UUID courseId) {
        return SummaryResponse.from(queryEnrollment.summary(courseId));
    }

    @PutMapping("/capacity")
    public SummaryResponse changeCapacity(@RequestParam UUID courseId,
                                          @RequestBody CapacityRequest request, Authentication auth) {
        return SummaryResponse.from(manageEnrollment.changeCapacity(courseId, request.capacity(), auth.getName()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EnrollmentResponse register(@Valid @RequestBody RegisterRequest request, Authentication auth) {
        return EnrollmentResponse.from(manageEnrollment.register(request.toCommand(), auth.getName()));
    }

    @GetMapping("/{id}")
    public EnrollmentResponse get(@PathVariable UUID id) { return EnrollmentResponse.from(queryEnrollment.get(id)); }

    @PostMapping("/{id}/confirm")
    public EnrollmentResponse confirm(@PathVariable UUID id, Authentication auth) {
        return EnrollmentResponse.from(manageEnrollment.confirm(id, auth.getName()));
    }

    @PostMapping("/{id}/cancel")
    public EnrollmentResponse cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request,
                                     Authentication auth) {
        return EnrollmentResponse.from(manageEnrollment.cancel(id, request.reason(), auth.getName()));
    }

    @PutMapping("/{id}")
    public EnrollmentResponse correct(@PathVariable UUID id, @Valid @RequestBody CorrectRequest request,
                                      Authentication auth) {
        return EnrollmentResponse.from(manageEnrollment.correct(id, request.applicantName(),
                request.applicantOrganization(), auth.getName()));
    }

    @PutMapping("/{id}/memo")
    public EnrollmentResponse memo(@PathVariable UUID id, @RequestBody MemoRequest request, Authentication auth) {
        return EnrollmentResponse.from(manageEnrollment.memo(id, request.memo(), auth.getName()));
    }

    public record RegisterRequest(UUID courseId, @NotBlank String applicantId, @NotBlank String applicantName,
                                  String applicantOrganization) {
        ManageEnrollmentUseCase.RegisterCommand toCommand() {
            return new ManageEnrollmentUseCase.RegisterCommand(courseId, applicantId, applicantName,
                    applicantOrganization);
        }
    }
    public record CancelRequest(@NotBlank String reason) { }
    public record CorrectRequest(@NotBlank String applicantName, String applicantOrganization) { }
    public record MemoRequest(String memo) { }
    /** 정원 없음은 {@code null} 로 보낸다 — 0 은 "아무도 안 받음"이라 뜻이 다르다. */
    public record CapacityRequest(Integer capacity) { }

    public record EnrollmentResponse(UUID id, UUID courseId, String applicantId, String applicantName,
                                     String applicantOrganization, EnrollmentStatus status, String adminMemo,
                                     String cancelReason, Instant appliedAt, Instant confirmedAt,
                                     Instant cancelledAt, String updatedBy, long version) {
        static EnrollmentResponse from(Enrollment e) {
            return new EnrollmentResponse(e.id(), e.courseId(), e.applicantId(), e.applicantName(),
                    e.applicantOrganization(), e.status(), e.adminMemo(), e.cancelReason(),
                    e.appliedAt(), e.confirmedAt(), e.cancelledAt(), e.updatedBy(), e.version());
        }
    }

    public record SummaryResponse(UUID courseId, String courseTitle, Integer capacity, Integer remaining,
                                  long confirmed, long waiting, long cancelled) {
        static SummaryResponse from(CapacitySummary s) {
            return new SummaryResponse(s.courseId(), s.courseTitle(), s.capacity(), s.remaining(),
                    s.confirmed(), s.waiting(), s.cancelled());
        }
    }
}
