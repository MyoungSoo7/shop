package github.lms.lemuel.operation.education.domain;

import github.lms.lemuel.operation.education.domain.exception.InvalidLecturerStateException;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 강사 애그리거트 루트 — 명부의 단일 진실원.
 *
 * <p>축이 둘이다. {@code active} 는 "지금 강의를 맡기는가", {@code deleted} 는 "명부에서 뺐는가".
 * dentis 는 use_yn 과 delete_yn 으로 같은 두 축을 썼고, 여기서도 합치지 않는다 — 합치면 잠시
 * 쉬는 강사를 되살리는 일과 삭제를 되돌리는 일이 같은 조작이 되어 버린다.
 *
 * <p>분야(전공·강의 분야)는 값의 집합이라 애그리거트가 통째로 소유한다. 중복은 애초에 담기지
 * 않는다 — 같은 전공이 두 번 붙은 강사는 화면에서 "보철, 보철" 로 읽히고, 지울 때 한 번만 지워진다.
 */
public final class Lecturer {
    private final UUID id;
    private String name;
    private String englishName;
    private String graduateSchool;
    private String officeName;
    private String career;
    private String lecturerType;
    private String historyKo;
    private String historyEn;
    private String etcMemo;
    private final Set<String> majors;
    private final Set<String> lectureFields;
    private boolean active;
    private boolean deleted;
    private Instant deletedAt;
    private String updatedBy;
    private final long version;

    private Lecturer(UUID id, String name, String englishName, String graduateSchool, String officeName,
                     String career, String lecturerType, String historyKo, String historyEn, String etcMemo,
                     Set<String> majors, Set<String> lectureFields, boolean active, boolean deleted,
                     Instant deletedAt, String updatedBy, long version) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        this.id = id;
        this.name = name;
        this.englishName = englishName;
        this.graduateSchool = graduateSchool;
        this.officeName = officeName;
        this.career = career;
        this.lecturerType = lecturerType;
        this.historyKo = historyKo;
        this.historyEn = historyEn;
        this.etcMemo = etcMemo;
        this.majors = normalize(majors);
        this.lectureFields = normalize(lectureFields);
        this.active = active;
        this.deleted = deleted;
        this.deletedAt = deletedAt;
        this.updatedBy = updatedBy;
        this.version = version;
    }

    public static Lecturer register(UUID id, String name, String englishName, String graduateSchool,
                                    String officeName, String career, String lecturerType,
                                    String historyKo, String historyEn, String etcMemo,
                                    Set<String> majors, Set<String> lectureFields, String actor) {
        return new Lecturer(id, name, englishName, graduateSchool, officeName, career, lecturerType,
                historyKo, historyEn, etcMemo, majors, lectureFields, true, false, null, actor, 0L);
    }

    /** 영속 상태에서 애그리거트를 되살린다 — 어댑터 전용 진입점. */
    public static Lecturer rehydrate(UUID id, String name, String englishName, String graduateSchool,
                                     String officeName, String career, String lecturerType,
                                     String historyKo, String historyEn, String etcMemo,
                                     Set<String> majors, Set<String> lectureFields,
                                     boolean active, boolean deleted, Instant deletedAt,
                                     String updatedBy, long version) {
        return new Lecturer(id, name, englishName, graduateSchool, officeName, career, lecturerType,
                historyKo, historyEn, etcMemo, majors, lectureFields, active, deleted, deletedAt,
                updatedBy, version);
    }

    /** 지운 강사는 고칠 수 없다 — 명부에서 뺀 사람의 이력이 조용히 바뀌면 지난 배정 기록의 뜻이 달라진다. */
    public void update(String name, String englishName, String graduateSchool, String officeName,
                       String career, String lecturerType, String historyKo, String historyEn,
                       String etcMemo, Set<String> majors, Set<String> lectureFields, String actor) {
        requireAlive();
        if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
        this.name = name;
        this.englishName = englishName;
        this.graduateSchool = graduateSchool;
        this.officeName = officeName;
        this.career = career;
        this.lecturerType = lecturerType;
        this.historyKo = historyKo;
        this.historyEn = historyEn;
        this.etcMemo = etcMemo;
        this.majors.clear();
        this.majors.addAll(normalize(majors));
        this.lectureFields.clear();
        this.lectureFields.addAll(normalize(lectureFields));
        this.updatedBy = actor;
    }

    public void activate(String actor) {
        requireAlive();
        this.active = true;
        this.updatedBy = actor;
    }

    public void deactivate(String actor) {
        requireAlive();
        this.active = false;
        this.updatedBy = actor;
    }

    /**
     * 명부에서 뺀다. 되돌리는 경로는 두지 않았다 — 두려면 "누가 언제 왜 되살렸나"가 함께 남아야
     * 하는데 그 기록 없이 부활시키면 삭제가 기록이 아니라 실수가 된다.
     */
    public void delete(String actor) {
        requireAlive();
        this.deleted = true;
        this.deletedAt = Instant.now();
        this.active = false;
        this.updatedBy = actor;
    }

    /** 배정 가능한 상태인지 — 지웠거나 쉬는 강사에게 새 과정을 맡기지 않는다. */
    public void ensureAssignable() {
        requireAlive();
        if (!active) throw new InvalidLecturerStateException("inactive lecturer cannot be assigned: " + id);
    }

    private void requireAlive() {
        if (deleted) throw new InvalidLecturerStateException("deleted lecturer cannot be modified: " + id);
    }

    /**
     * 공백 항목을 걷어내고 중복을 없앤다. 같은 전공이 두 번 붙으면 화면에 "보철, 보철" 로 읽히고
     * 지울 때 한 번만 지워진다. (저장 후 다시 읽으면 이름 오름차순이 된다 — 컬렉션 테이블에
     * 순서 컬럼이 없기 때문이며, 영속 어댑터가 그렇게 되살린다.)
     */
    private static Set<String> normalize(Set<String> values) {
        Set<String> result = new LinkedHashSet<>();
        if (values == null) return result;
        for (String value : values) {
            if (value == null) continue;
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result;
    }

    public UUID id() { return id; }
    public String name() { return name; }
    public String englishName() { return englishName; }
    public String graduateSchool() { return graduateSchool; }
    public String officeName() { return officeName; }
    public String career() { return career; }
    public String lecturerType() { return lecturerType; }
    public String historyKo() { return historyKo; }
    public String historyEn() { return historyEn; }
    public String etcMemo() { return etcMemo; }
    public List<String> majors() { return List.copyOf(majors); }
    public List<String> lectureFields() { return List.copyOf(lectureFields); }
    public boolean active() { return active; }
    public boolean deleted() { return deleted; }
    public Instant deletedAt() { return deletedAt; }
    public String updatedBy() { return updatedBy; }
    public long version() { return version; }
}
