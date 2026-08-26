package github.lms.lemuel.operation.education.domain;

import github.lms.lemuel.operation.education.domain.exception.InvalidLecturerStateException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 강사 애그리거트 — <b>두 축(active / deleted)이 섞이지 않는가</b>.
 *
 * <p>dentis 는 use_yn 과 delete_yn 두 컬럼을 뒀지만 화면에서는 둘 다 "사용 여부"처럼 보였다.
 * 여기서 고정하는 것은 그 둘이 서로 다른 결정이라는 점이다 — 쉬는 강사는 되돌릴 수 있고,
 * 지운 강사는 손댈 수 없다.
 */
class LecturerTest {

    private Lecturer registered() {
        return Lecturer.register(UUID.randomUUID(), "김강사", "Kim", "OO대학원", "OO치과",
                "10년", "외부 강사", "약력", "history", "메모",
                ordered("보철", "임플란트"), ordered("보철 실습"), "admin");
    }

    /** 입력 순서를 지키는 집합 — {@code Set.of} 는 순서가 없고 중복 인자를 예외로 거절한다. */
    private static Set<String> ordered(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }

    @Test
    @DisplayName("등록하면 활성이고 삭제 상태가 아니다")
    void registersActive() {
        Lecturer lecturer = registered();

        assertThat(lecturer.active()).isTrue();
        assertThat(lecturer.deleted()).isFalse();
        assertThat(lecturer.deletedAt()).isNull();
        assertThat(lecturer.majors()).containsExactly("보철", "임플란트");
    }

    @Test
    @DisplayName("이름은 필수다 — 이름 없는 강사는 목록에서 고를 수가 없다")
    void nameIsRequired() {
        assertThatThrownBy(() -> Lecturer.register(UUID.randomUUID(), "  ", null, null, null, null, null,
                null, null, null, Set.of(), Set.of(), "admin"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("분야의 공백과 중복은 담기지 않는다 — '보철, 보철'로 읽히고 지울 때 한 번만 지워진다")
    void normalizesFields() {
        Lecturer lecturer = Lecturer.register(UUID.randomUUID(), "김강사", null, null, null, null, null,
                null, null, null, ordered("보철", " 보철 ", "  ", "임플란트"), ordered(""), "admin");

        assertThat(lecturer.majors()).containsExactly("보철", "임플란트");
        assertThat(lecturer.lectureFields()).isEmpty();
    }

    @Test
    @DisplayName("비활성은 되돌릴 수 있다 — 잠시 쉬는 것과 명부에서 빼는 것은 다르다")
    void deactivateIsReversible() {
        Lecturer lecturer = registered();

        lecturer.deactivate("admin");
        assertThat(lecturer.active()).isFalse();
        assertThat(lecturer.deleted()).isFalse();

        lecturer.activate("admin");
        assertThat(lecturer.active()).isTrue();
    }

    @Test
    @DisplayName("비활성 강사는 새 과정을 못 맡는다")
    void inactiveCannotBeAssigned() {
        Lecturer lecturer = registered();
        lecturer.deactivate("admin");

        assertThatThrownBy(lecturer::ensureAssignable)
                .isInstanceOf(InvalidLecturerStateException.class);
    }

    @Test
    @DisplayName("삭제는 비활성까지 끌고 간다 — 명부에서 뺀 사람이 '활성'으로 남으면 배정 후보로 보인다")
    void deleteAlsoDeactivates() {
        Lecturer lecturer = registered();

        lecturer.delete("admin");

        assertThat(lecturer.deleted()).isTrue();
        assertThat(lecturer.active()).isFalse();
        assertThat(lecturer.deletedAt()).isNotNull();
    }

    @Test
    @DisplayName("지운 강사는 수정·활성화·재삭제 어느 것도 안 된다")
    void deletedIsFrozen() {
        Lecturer lecturer = registered();
        lecturer.delete("admin");

        assertThatThrownBy(() -> lecturer.update("새 이름", null, null, null, null, null, null, null,
                null, Set.of(), Set.of(), "admin"))
                .isInstanceOf(InvalidLecturerStateException.class);
        assertThatThrownBy(() -> lecturer.activate("admin"))
                .isInstanceOf(InvalidLecturerStateException.class);
        assertThatThrownBy(() -> lecturer.delete("admin"))
                .isInstanceOf(InvalidLecturerStateException.class);
        assertThatThrownBy(lecturer::ensureAssignable)
                .isInstanceOf(InvalidLecturerStateException.class);
    }

    @Test
    @DisplayName("수정은 분야를 통째로 갈아 끼운다 — 빠진 항목은 지워진다")
    void updateReplacesFields() {
        Lecturer lecturer = registered();

        lecturer.update("김강사", "Kim", "OO대학원", "△△치과", "12년", "내부 강사", "약력2", "history2",
                "메모2", ordered("교정"), ordered("교정 실습", "보철 실습"), "editor");

        assertThat(lecturer.majors()).containsExactly("교정");
        assertThat(lecturer.lectureFields()).containsExactlyInAnyOrder("교정 실습", "보철 실습");
        assertThat(lecturer.officeName()).isEqualTo("△△치과");
        assertThat(lecturer.updatedBy()).isEqualTo("editor");
    }

    @Test
    @DisplayName("null 분야는 빈 목록과 같다 — 화면이 입력을 안 보낸 경우에 NPE 로 죽지 않는다")
    void nullFieldsAreEmpty() {
        Lecturer lecturer = Lecturer.register(UUID.randomUUID(), "김강사", null, null, null, null, null,
                null, null, null, null, null, "admin");

        assertThat(lecturer.majors()).isEmpty();
        assertThat(lecturer.lectureFields()).isEmpty();
    }

    @Test
    @DisplayName("majors() 는 사본이라 밖에서 고쳐도 애그리거트가 안 변한다")
    void collectionsAreCopies() {
        Lecturer lecturer = registered();

        assertThatThrownBy(() -> lecturer.majors().add("교정"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(lecturer.majors()).containsExactly("보철", "임플란트");
    }
}
