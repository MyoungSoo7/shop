package github.lms.lemuel.operation.education.adapter.in.web;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.application.port.in.ManageLecturerUseCase;
import github.lms.lemuel.operation.education.application.port.in.QueryLecturerUseCase;
import github.lms.lemuel.operation.education.domain.Lecturer;
import github.lms.lemuel.operation.education.domain.LecturerAssignment;
import github.lms.lemuel.operation.education.domain.exception.AssignmentNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 강사 명부 콘솔 — {@code /admin/education/lecturers}.
 *
 * <p>경로가 {@code /admin/education/**} 아래인 덕에 {@code EducationSecurityConfig}(@Order(4)) 의
 * ADMIN 게이팅이 그대로 적용된다. 새 필터체인을 만들면 같은 규칙이 두 곳에 생기고 둘이 어긋난다.
 */
@RestController
@RequestMapping("/admin/education/lecturers")
public class AdminLecturerController {

    private final QueryLecturerUseCase queryLecturer;
    private final ManageLecturerUseCase manageLecturer;

    public AdminLecturerController(QueryLecturerUseCase queryLecturer, ManageLecturerUseCase manageLecturer) {
        this.queryLecturer = queryLecturer;
        this.manageLecturer = manageLecturer;
    }

    @GetMapping
    public Page<LecturerResponse> list(@RequestParam(defaultValue = "") String keyword,
                                       @RequestParam(defaultValue = "false") boolean activeOnly,
                                       Pageable pageable) {
        PageSlice<Lecturer> slice = queryLecturer.list(keyword, activeOnly,
                new PageSpec(pageable.getPageNumber(), pageable.getPageSize()));
        // 응답 JSON 모양(content/totalElements/totalPages/number/size)을 유지하려고 여기서만 Page 로 되싼다.
        return new PageImpl<>(slice.content().stream().map(LecturerResponse::from).toList(),
                PageRequest.of(slice.page(), slice.size()), slice.totalElements());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LecturerResponse register(@Valid @RequestBody SaveRequest request, Authentication auth) {
        return LecturerResponse.from(manageLecturer.register(request.toCommand(), auth.getName()));
    }

    @GetMapping("/{id}")
    public LecturerResponse get(@PathVariable UUID id) { return LecturerResponse.from(queryLecturer.get(id)); }

    @PutMapping("/{id}")
    public LecturerResponse update(@PathVariable UUID id, @Valid @RequestBody SaveRequest request,
                                   Authentication auth) {
        return LecturerResponse.from(manageLecturer.update(id, request.toCommand(), auth.getName()));
    }

    @PutMapping("/{id}/activation")
    public LecturerResponse changeActivation(@PathVariable UUID id,
                                             @Valid @RequestBody ActivationRequest request,
                                             Authentication auth) {
        return LecturerResponse.from(manageLecturer.changeActivation(id, request.active(), auth.getName()));
    }

    /** 명부에서 뺀다. 204 가 아니라 바뀐 강사를 돌려준다 — 화면이 삭제 표시를 즉시 그릴 수 있어야 한다. */
    @DeleteMapping("/{id}")
    public LecturerResponse delete(@PathVariable UUID id, Authentication auth) {
        return LecturerResponse.from(manageLecturer.delete(id, auth.getName()));
    }

    @GetMapping("/{id}/courses")
    public List<AssignmentResponse> assignments(@PathVariable UUID id) {
        return queryLecturer.assignmentsOfLecturer(id).stream().map(AssignmentResponse::from).toList();
    }

    @PostMapping("/{id}/courses")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentResponse assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request,
                                     Authentication auth) {
        return AssignmentResponse.from(manageLecturer.assign(id, request.courseId(), auth.getName()));
    }

    /**
     * 배정 해제. 없던 배정이면 404 다 — 200 을 돌려주면 화면은 "해제됐다"고 그리지만 실제로는
     * 다른 과정의 배정이 그대로 남아 있는 상태와 구분되지 않는다.
     */
    @DeleteMapping("/{id}/courses/{courseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(@PathVariable UUID id, @PathVariable UUID courseId, Authentication auth) {
        if (!manageLecturer.unassign(id, courseId, auth.getName())) {
            throw new AssignmentNotFoundException(courseId, id);
        }
    }

    /** 그 과정에 배정된 강사들 — 과정 화면이 "누가 가르치나"를 물을 때. */
    @GetMapping("/by-course/{courseId}")
    public List<AssignmentResponse> byCourse(@PathVariable UUID courseId) {
        return queryLecturer.assignmentsOfCourse(courseId).stream().map(AssignmentResponse::from).toList();
    }

    /**
     * 등록·수정 요청. 분야 둘은 목록으로 받아 중복·공백을 도메인이 걸러 낸다.
     *
     * <p>{@code null} 목록은 빈 목록과 같게 다룬다 — 화면이 분야 입력을 아예 안 보낸 경우와
     * "다 지웠다"를 구분하려면 PATCH 가 필요한데, 이 화면은 폼 전체를 항상 보낸다.
     */
    public record SaveRequest(@NotBlank String name, String englishName, String graduateSchool,
                              String officeName, String career, String lecturerType,
                              String historyKo, String historyEn, String etcMemo,
                              List<String> majors, List<String> lectureFields) {
        Set<String> majorSet() { return majors == null ? Set.of() : new java.util.LinkedHashSet<>(majors); }
        Set<String> lectureFieldSet() {
            return lectureFields == null ? Set.of() : new java.util.LinkedHashSet<>(lectureFields);
        }
        ManageLecturerUseCase.SaveCommand toCommand() {
            return new ManageLecturerUseCase.SaveCommand(name, englishName, graduateSchool, officeName,
                    career, lecturerType, historyKo, historyEn, etcMemo, majorSet(), lectureFieldSet());
        }
    }

    public record ActivationRequest(@NotNull Boolean active) { }

    public record AssignRequest(@NotNull UUID courseId) { }

    public record LecturerResponse(UUID id, String name, String englishName, String graduateSchool,
                                   String officeName, String career, String lecturerType,
                                   String historyKo, String historyEn, String etcMemo,
                                   List<String> majors, List<String> lectureFields,
                                   boolean active, boolean deleted, Instant deletedAt,
                                   String updatedBy, long version) {
        static LecturerResponse from(Lecturer l) {
            return new LecturerResponse(l.id(), l.name(), l.englishName(), l.graduateSchool(),
                    l.officeName(), l.career(), l.lecturerType(), l.historyKo(), l.historyEn(),
                    l.etcMemo(), l.majors(), l.lectureFields(), l.active(), l.deleted(), l.deletedAt(),
                    l.updatedBy(), l.version());
        }
    }

    public record AssignmentResponse(UUID id, UUID courseId, String courseTitle, UUID lecturerId,
                                     String lecturerName, Instant assignedAt, String assignedBy) {
        static AssignmentResponse from(LecturerAssignment a) {
            return new AssignmentResponse(a.id(), a.courseId(), a.courseTitle(), a.lecturerId(),
                    a.lecturerName(), a.assignedAt(), a.assignedBy());
        }
    }
}
