package github.lms.lemuel.operation.education.adapter.in.web;

import github.lms.lemuel.operation.education.application.port.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.dto.PageSpec;
import github.lms.lemuel.operation.education.application.port.in.ManageCourseUseCase;
import github.lms.lemuel.operation.education.application.port.in.ManageLessonUseCase;
import github.lms.lemuel.operation.education.application.port.in.QueryCourseUseCase;
import github.lms.lemuel.operation.education.application.port.in.QueryLessonUseCase;
import github.lms.lemuel.operation.education.domain.Course;
import github.lms.lemuel.operation.education.domain.CourseStatus;
import github.lms.lemuel.operation.education.domain.Lesson;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/admin/education/courses")
public class AdminEducationController {
    private final QueryCourseUseCase queryCourse;
    private final ManageCourseUseCase manageCourse;
    private final QueryLessonUseCase queryLesson;
    private final ManageLessonUseCase manageLesson;

    public AdminEducationController(QueryCourseUseCase queryCourse, ManageCourseUseCase manageCourse,
                                    QueryLessonUseCase queryLesson, ManageLessonUseCase manageLesson) {
        this.queryCourse = queryCourse;
        this.manageCourse = manageCourse;
        this.queryLesson = queryLesson;
        this.manageLesson = manageLesson;
    }

    @GetMapping
    public Page<CourseResponse> list(@RequestParam(required = false) CourseStatus status,
                                     @RequestParam(defaultValue = "") String query, Pageable pageable) {
        PageSlice<Course> slice = queryCourse.list(status, query,
                new PageSpec(pageable.getPageNumber(), pageable.getPageSize()));
        // 응답 JSON 모양(content/totalElements/totalPages/number/size)을 유지하려고 여기서만 Page 로 되싼다.
        return new PageImpl<>(slice.content().stream().map(CourseResponse::from).toList(),
                PageRequest.of(slice.page(), slice.size()), slice.totalElements());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse create(@Valid @RequestBody CourseRequest request, Authentication auth) {
        return CourseResponse.from(manageCourse.create(request.toCommand(), auth.getName()));
    }

    @GetMapping("/{id}")
    public CourseResponse get(@PathVariable UUID id) { return CourseResponse.from(queryCourse.get(id)); }

    @PutMapping("/{id}")
    public CourseResponse update(@PathVariable UUID id, @Valid @RequestBody CourseRequest request, Authentication auth) {
        return CourseResponse.from(manageCourse.update(id, request.toCommand(), auth.getName()));
    }

    @PostMapping("/{id}/publish")
    public CourseResponse publish(@PathVariable UUID id, Authentication auth) { return transition(id, CourseStatus.PUBLISHED, auth); }
    @PostMapping("/{id}/hide")
    public CourseResponse hide(@PathVariable UUID id, Authentication auth) { return transition(id, CourseStatus.HIDDEN, auth); }
    @PostMapping("/{id}/close")
    public CourseResponse close(@PathVariable UUID id, Authentication auth) { return transition(id, CourseStatus.CLOSED, auth); }

    @GetMapping("/{courseId}/lessons")
    public List<LessonResponse> lessons(@PathVariable UUID courseId) {
        return queryLesson.list(courseId).stream().map(LessonResponse::from).toList();
    }

    @PostMapping("/{courseId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public LessonResponse createLesson(@PathVariable UUID courseId, @Valid @RequestBody LessonRequest request, Authentication auth) {
        return LessonResponse.from(
                manageLesson.create(courseId, request.sequence(), request.toCommand(), auth.getName()));
    }

    // courseId 를 받아 유스케이스로 넘긴다 — 경로가 "이 과정의 이 차시"라고 말하면 서버도 그렇게
    // 확인해야 한다. 바인딩하지 않던 동안에는 lessonId 만 맞으면 어떤 courseId 로 불러도 통과했다.
    @PutMapping("/{courseId}/lessons/{lessonId}")
    public LessonResponse updateLesson(@PathVariable UUID courseId, @PathVariable UUID lessonId, @Valid @RequestBody LessonRequest request, Authentication auth) {
        return LessonResponse.from(
                manageLesson.update(courseId, lessonId, request.toCommand(), auth.getName()));
    }

    @DeleteMapping("/{courseId}/lessons/{lessonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLesson(@PathVariable UUID courseId, @PathVariable UUID lessonId, Authentication auth) { manageLesson.delete(courseId, lessonId, auth.getName()); }

    @PostMapping("/{courseId}/lessons/reorder")
    public List<LessonResponse> reorder(@PathVariable UUID courseId, @RequestBody ReorderRequest request,
                                        Authentication auth) {
        manageLesson.reorder(courseId, request.lessonIds(), auth.getName());
        return lessons(courseId);
    }

    private CourseResponse transition(UUID id, CourseStatus status, Authentication auth) {
        return CourseResponse.from(manageCourse.transition(id, status, auth.getName()));
    }

    public record CourseRequest(@NotBlank String title, String description) {
        ManageCourseUseCase.SaveCommand toCommand() {
            return new ManageCourseUseCase.SaveCommand(title, description);
        }
    }
    public record CourseResponse(UUID id, String title, String description, CourseStatus status,
                                 String updatedBy, long version) {
        static CourseResponse from(Course c) {
            return new CourseResponse(c.id(), c.title(), c.description(), c.status(), c.updatedBy(), c.version());
        }
    }
    public record ReorderRequest(List<UUID> lessonIds) { }
    /**
     * 차시 등록·수정 요청. {@code sequence} 는 등록에서만 쓰인다 — 수정은 순서를 건드리지 않는다
     * (재정렬은 {@code /lessons/reorder} 하나가 맡는다).
     */
    public record LessonRequest(@NotBlank String title, String description, int sequence, @NotBlank String contentType,
                                @NotBlank String contentRef, boolean required) {
        ManageLessonUseCase.SaveCommand toCommand() {
            return new ManageLessonUseCase.SaveCommand(title, description, contentType, contentRef, required);
        }
    }
    public record LessonResponse(UUID id, UUID courseId, String title, int sequence, String contentType, String contentRef) {
        static LessonResponse from(Lesson l) { return new LessonResponse(l.id(), l.courseId(), l.title(), l.sequence(), l.contentType().name(), l.contentRef()); }
    }
}
