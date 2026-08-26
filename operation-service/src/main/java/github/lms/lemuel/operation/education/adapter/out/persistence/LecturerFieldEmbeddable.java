package github.lms.lemuel.operation.education.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.util.Objects;

/**
 * 강사 분야 한 칸 — (kind, name) 쌍이다. {@code @ElementCollection} 의 원소라
 * {@code equals}/{@code hashCode} 가 값 기준이어야 한다(아니면 하이버네이트가 매번 전량
 * 삭제·재삽입한다).
 *
 * <p>kind 문자열을 enum 이 아니라 상수로 둔 이유: 이 값은 어댑터 안에서만 살고 도메인은
 * majors/lectureFields 두 목록으로만 안다. enum 을 만들면 도메인이 그 타입을 알아야 하거나
 * 어댑터 타입이 포트로 새어 나간다.
 */
@Embeddable
public class LecturerFieldEmbeddable {

    static final String MAJOR = "MAJOR";
    static final String LECTURE = "LECTURE";

    @Column(name = "field_kind", nullable = false)
    private String fieldKind;

    @Column(name = "field_name", nullable = false)
    private String fieldName;

    protected LecturerFieldEmbeddable() { }

    LecturerFieldEmbeddable(String fieldKind, String fieldName) {
        this.fieldKind = fieldKind;
        this.fieldName = fieldName;
    }

    String getFieldKind() { return fieldKind; }

    String getFieldName() { return fieldName; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof LecturerFieldEmbeddable that)) return false;
        return Objects.equals(fieldKind, that.fieldKind) && Objects.equals(fieldName, that.fieldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fieldKind, fieldName);
    }
}
