package github.lms.lemuel.operation.education.adapter.in.web;

import github.lms.lemuel.operation.education.application.port.out.dto.PageSlice;
import github.lms.lemuel.operation.education.application.port.out.dto.PageSpec;
import github.lms.lemuel.operation.education.application.service.CourseAdminService;
import github.lms.lemuel.operation.education.application.service.LessonAdminService;
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
    private final CourseAdminService service;
    private final LessonAdminService lessonService;
    public AdminEducationController(CourseAdminService service, LessonAdminService lessonService) { this.service = service; this.lessonService = lessonService; }

    @GetMapping
    public Page<CourseResponse> list(@RequestParam(required = false) CourseStatus status,
                                     @RequestParam(defaultValue = "") String query, Pageable pageable) {
        PageSlice<Course> slice = service.list(status, query, new PageSpec(pageable.getPageNumber(), pageable.getPageSize()));
        // 응답 JSON 모양(content/totalElements/totalPages/number/size)을 유지하려고 여기서만 Page 로 되싼다.
        return new PageImpl<>(slice.content().stream().map(CourseResponse::from).toList(),
                PageRequest.of(slice.page(), slice.size()), slice.totalElements());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CourseResponse create(@Valid @RequestBody CourseRequest request, Authentication auth) {
        return CourseResponse.from(service.create(request.title(), request.description(), auth.getName()));
    }

    @GetMapping("/{id}")
    public CourseResponse get(@PathVariable UUID id) { return CourseResponse.from(service.get(id)); }

    @PutMapping("/{id}")
    public CourseResponse update(@PathVariable UUID id, @Valid @RequestBody CourseRequest request, Authentication auth) {
        return CourseResponse.from(service.update(id, request.title(), request.description(), auth.getName()));
    }

    @PostMapping("/{id}/publish")
    public CourseResponse publish(@PathVariable UUID id, Authentication auth) { return transition(id, CourseStatus.PUBLISHED, auth); }
    @PostMapping("/{id}/hide")
    public CourseResponse hide(@PathVariable UUID id, Authentication auth) { return transition(id, CourseStatus.HIDDEN, auth); }
    @PostMapping("/{id}/close")
    public CourseResponse close(@PathVariable UUID id, Authentication auth) { return transition(id, CourseStatus.CLOSED, auth); }

    @GetMapping("/{courseId}/lessons")
    public List<LessonResponse> lessons(@PathVariable UUID courseId) {
        return lessonService.list(courseId).stream().map(LessonResponse::from).toList();
    }

    @PostMapping("/{courseId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public LessonResponse createLesson(@PathVariable UUID courseId, @Valid @RequestBody LessonRequest request, Authentication auth) {
        return LessonResponse.from(lessonService.create(courseId, request.title(), request.description(), request.sequence(), request.contentType(), request.contentRef(), request.required(), auth.getName()));
    }

    // courseId 를 받아 서비스로 넘긴다 — 경로가 "이 과정의 이 차시"라고 말하면 서버도 그렇게
    // 확인해야 한다. 바인딩하지 않던 동안에는 lessonId 만 맞으면 어떤 courseId 로 불러도 통과했다.
    @PutMapping("/{courseId}/lessons/{lessonId}")
    public LessonResponse updateLesson(@PathVariable UUID courseId, @PathVariable UUID lessonId, @Valid @RequestBody LessonRequest request, Authentication auth) {
        return LessonResponse.from(lessonService.update(courseId, lessonId, request.title(), request.description(), request.contentType(), request.contentRef(), request.required(), auth.getName()));
    }

    @DeleteMapping("/{courseId}/lessons/{lessonId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteLesson(@PathVariable UUID courseId, @PathVariable UUID lessonId, Authentication auth) { lessonService.delete(courseId, lessonId, auth.getName()); }

    @PostMapping("/{courseId}/lessons/reorder")
    public List<LessonResponse> reorder(@PathVariable UUID courseId, @RequestBody ReorderRequest request,
                                        Authentication auth) {
        lessonService.reorder(courseId, request.lessonIds(), auth.getName());
        return lessons(courseId);
    }

    private CourseResponse transition(UUID id, CourseStatus status, Authentication auth) {
        return CourseResponse.from(service.transition(id, status, auth.getName()));
    }

    public record CourseRequest(@NotBlank String title, String description) { }
    public record CourseResponse(UUID id, String title, String description, CourseStatus status,
                                 String updatedBy, long version) {
        static CourseResponse from(Course c) {
            return new CourseResponse(c.id(), c.title(), c.description(), c.status(), c.updatedBy(), c.version());
        }
    }
    public record ReorderRequest(List<UUID> lessonIds) { }
    public record LessonRequest(@NotBlank String title, String description, int sequence, @NotBlank String contentType,
                                @NotBlank String contentRef, boolean required) { }
    public record LessonResponse(UUID id, UUID courseId, String title, int sequence, String contentType, String contentRef) {
        static LessonResponse from(Lesson l) { return new LessonResponse(l.id(), l.courseId(), l.title(), l.sequence(), l.contentType().name(), l.contentRef()); }
    }
}
