package github.lms.lemuel.operation.education.application.service;

import github.lms.lemuel.operation.education.application.port.out.DeleteLessonPort;
import github.lms.lemuel.operation.education.application.port.out.EducationAuditPort;
import github.lms.lemuel.operation.education.application.port.out.LoadLessonPort;
import github.lms.lemuel.operation.education.application.port.out.SaveLessonPort;
import github.lms.lemuel.operation.education.domain.Lesson;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class LessonAdminService {
    private final LoadLessonPort loadLesson;
    private final SaveLessonPort saveLesson;
    private final DeleteLessonPort deleteLesson;
    private final EducationAuditPort audit;

    public LessonAdminService(LoadLessonPort loadLesson, SaveLessonPort saveLesson, DeleteLessonPort deleteLesson) {
        this(loadLesson, saveLesson, deleteLesson, (a, t, id, actor, detail) -> { });
    }

    @Autowired
    public LessonAdminService(LoadLessonPort loadLesson, SaveLessonPort saveLesson,
                              DeleteLessonPort deleteLesson, EducationAuditPort audit) {
        this.loadLesson = loadLesson;
        this.saveLesson = saveLesson;
        this.deleteLesson = deleteLesson;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<Lesson> list(UUID courseId) { return loadLesson.findByCourseOrderedBySequence(courseId); }

    @Transactional
    public Lesson create(UUID courseId, String title, String description, int sequence,
                         String type, String ref, boolean required, String actor) {
        Lesson lesson = saveLesson.save(
                Lesson.create(UUID.randomUUID(), courseId, title, description, sequence, type, ref, required, actor));
        audit.record("LESSON_CREATED", "Lesson", lesson.id(), actor, "lesson created");
        return lesson;
    }

    @Transactional
    public Lesson update(UUID courseId, UUID id, String title, String description, String type, String ref,
                         boolean required, String actor) {
        Lesson lesson = loadLesson.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("lesson not found: " + id));
        lesson.requireBelongsTo(courseId);
        lesson.update(title, description, type, ref, required, actor);
        Lesson saved = saveLesson.save(lesson);
        audit.record("LESSON_UPDATED", "Lesson", id, actor, "lesson updated");
        return saved;
    }

    @Transactional
    public void delete(UUID courseId, UUID id, String actor) {
        // 없는 차시의 삭제는 이전처럼 조용히 통과시킨다(삭제는 멱등). 존재하는데 다른 과정 소속이면
        // 거부한다 — 지우고 나서야 "그 과정이 아니었다"는 사실을 알게 되면 되돌릴 방법이 없다.
        loadLesson.findById(id).ifPresent(lesson -> lesson.requireBelongsTo(courseId));
        deleteLesson.deleteById(id);
        audit.record("LESSON_DELETED", "Lesson", id, actor, "lesson deleted");
    }

    /**
     * 요청 순서대로 차시를 재정렬한다.
     *
     * <p>2단으로 저장하는 이유 — {@code education_lessons} 에 {@code (course_id, sequence)} 유니크
     * 제약이 있어서, 두 차시의 순서를 맞바꾸면 중간 상태에서 같은 값이 잠깐 겹친다. 그래서 먼저
     * 음수 구간으로 전부 밀어 두고(충돌 불가), 그 다음 목표 순서를 쓴다.
     */
    @Transactional
    public void reorder(UUID courseId, List<UUID> requestedIds, String actor) {
        List<Lesson> current = loadLesson.findByCourseOrderedBySequence(courseId);
        Lesson.validateReorder(current.stream().map(Lesson::id).toList(), requestedIds);

        Map<UUID, Lesson> byId = new HashMap<>();
        for (Lesson lesson : current) byId.put(lesson.id(), lesson);

        for (int i = 0; i < requestedIds.size(); i++) {
            Lesson lesson = byId.get(requestedIds.get(i));
            lesson.changeSequence(-(i + 1), actor);
            saveLesson.save(lesson);
        }
        for (int i = 0; i < requestedIds.size(); i++) {
            Lesson lesson = byId.get(requestedIds.get(i));
            lesson.changeSequence(i + 1, actor);
            saveLesson.save(lesson);
        }
        audit.record("LESSON_REORDERED", "Course", courseId, actor, "lesson order changed");
    }
}
