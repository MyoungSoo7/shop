package github.lms.lemuel.operation.education.adapter.out.persistence;

import github.lms.lemuel.operation.education.domain.Lecturer;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * 강사 영속 모델 — 매핑만 한다. 규칙은 도메인 {@link Lecturer} 가 소유한다.
 *
 * <p>분야는 한 테이블에 kind 로 두 축을 담는다. 그래서 {@code @ElementCollection} 두 개가 같은
 * 테이블을 가리키되 각각 {@code @Where} 대신 <b>한 컬렉션</b>으로 읽고 서비스가 나누는 편이
 * 안전하다 — 같은 테이블에 컬렉션 둘을 걸면 하이버네이트가 한쪽을 지울 때 다른 쪽 행까지
 * 지운다(같은 조인 키로 delete-all-then-insert 를 하기 때문이다).
 */
@Entity
@Table(name = "education_lecturers", schema = "education")
public class LecturerJpaEntity {

    @Id private UUID id;
    private String name;
    private String englishName;
    private String graduateSchool;
    private String officeName;
    private String career;
    private String lecturerType;
    @Column(columnDefinition = "TEXT") private String historyKo;
    @Column(columnDefinition = "TEXT") private String historyEn;
    private String etcMemo;
    private boolean active;
    private boolean deleted;
    private Instant deletedAt;
    private String createdBy;
    private String updatedBy;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "education_lecturer_fields", schema = "education",
            joinColumns = @JoinColumn(name = "lecturer_id"))
    private Set<LecturerFieldEmbeddable> fields = new LinkedHashSet<>();

    protected LecturerJpaEntity() { }

    static LecturerJpaEntity fromDomain(Lecturer lecturer) {
        LecturerJpaEntity entity = new LecturerJpaEntity();
        entity.id = lecturer.id();
        entity.createdBy = lecturer.updatedBy();
        entity.createdAt = Instant.now();
        entity.sync(lecturer);
        return entity;
    }

    /** 도메인 상태를 영속 모델에 반영한다 — 식별자·등록자·등록 시각은 건드리지 않는다. */
    void sync(Lecturer lecturer) {
        this.name = lecturer.name();
        this.englishName = lecturer.englishName();
        this.graduateSchool = lecturer.graduateSchool();
        this.officeName = lecturer.officeName();
        this.career = lecturer.career();
        this.lecturerType = lecturer.lecturerType();
        this.historyKo = lecturer.historyKo();
        this.historyEn = lecturer.historyEn();
        this.etcMemo = lecturer.etcMemo();
        this.active = lecturer.active();
        this.deleted = lecturer.deleted();
        this.deletedAt = lecturer.deletedAt();
        this.updatedBy = lecturer.updatedBy();
        this.updatedAt = Instant.now();
        this.fields.clear();
        for (String major : lecturer.majors()) {
            this.fields.add(new LecturerFieldEmbeddable(LecturerFieldEmbeddable.MAJOR, major));
        }
        for (String lectureField : lecturer.lectureFields()) {
            this.fields.add(new LecturerFieldEmbeddable(LecturerFieldEmbeddable.LECTURE, lectureField));
        }
    }

    Lecturer toDomain() {
        return Lecturer.rehydrate(id, name, englishName, graduateSchool, officeName, career, lecturerType,
                historyKo, historyEn, etcMemo,
                fieldsOf(LecturerFieldEmbeddable.MAJOR), fieldsOf(LecturerFieldEmbeddable.LECTURE),
                active, deleted, deletedAt, updatedBy, version);
    }

    /**
     * 이름 오름차순으로 되살린다. 컬렉션 테이블에는 순서 컬럼이 없어 DB 가 돌려주는 순서는
     * 보장되지 않는다 — 정렬하지 않으면 같은 강사를 두 번 열었을 때 분야 순서가 달라 보인다.
     */
    private Set<String> fieldsOf(String kind) {
        Set<String> result = new TreeSet<>();
        for (LecturerFieldEmbeddable field : fields) {
            if (kind.equals(field.getFieldKind())) result.add(field.getFieldName());
        }
        return result;
    }

    public UUID getId() { return id; }
}
