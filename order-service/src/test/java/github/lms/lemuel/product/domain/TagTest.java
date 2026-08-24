package github.lms.lemuel.product.domain;
import github.lms.lemuel.product.domain.exception.InvalidProductStateException;
import github.lms.lemuel.product.domain.exception.ProductInvariantViolationException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.*;

class TagTest {

    @Test @DisplayName("create: 유효한 이름과 색상으로 태그를 생성한다")
    void create_valid() {
        Tag tag = Tag.create("신상품", "#EF4444");
        assertThat(tag.getName()).isEqualTo("신상품");
        assertThat(tag.getColor()).isEqualTo("#EF4444");
        assertThat(tag.getCreatedAt()).isNotNull();
    }

    @Test @DisplayName("create: null 이름이면 예외")
    void create_nullName() {
        assertThatThrownBy(() -> Tag.create(null, "#FFFFFF"))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessage("Tag name cannot be empty");
    }

    @Test @DisplayName("create: 빈 이름이면 예외")
    void create_emptyName() {
        assertThatThrownBy(() -> Tag.create("  ", "#FFFFFF"))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test @DisplayName("create: 50자 초과 이름이면 예외")
    void create_longName() {
        assertThatThrownBy(() -> Tag.create("A".repeat(51), "#FFFFFF"))
                .isInstanceOf(ProductInvariantViolationException.class)
                .hasMessage("Tag name must not exceed 50 characters");
    }

    @Test @DisplayName("create: 잘못된 색상 형식이면 예외")
    void create_invalidColor() {
        assertThatThrownBy(() -> Tag.create("태그", "red"))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test @DisplayName("create: null 색상이면 예외")
    void create_nullColor() {
        assertThatThrownBy(() -> Tag.create("태그", null))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test @DisplayName("기본 생성자: 기본 색상이 #6B7280이다")
    void defaultConstructor_defaultColor() {
        Tag tag = new Tag();
        assertThat(tag.getColor()).isEqualTo("#6B7280");
        assertThat(tag.getCreatedAt()).isNotNull();
    }

    @Test @DisplayName("전체 생성자: null 색상이면 기본값")
    void fullConstructor_nullColor() {
        Tag tag = new Tag(1L, "태그", null, LocalDateTime.now());
        assertThat(tag.getColor()).isEqualTo("#6B7280");
    }

    @Test @DisplayName("전체 생성자: null createdAt이면 현재 시간")
    void fullConstructor_nullCreatedAt() {
        Tag tag = new Tag(1L, "태그", "#AABBCC", null);
        assertThat(tag.getCreatedAt()).isNotNull();
    }

    @Test @DisplayName("updateInfo: 이름과 색상을 업데이트한다")
    void updateInfo_both() {
        Tag tag = Tag.create("원본", "#000000");
        tag.updateInfo("수정됨", "#FFFFFF");
        assertThat(tag.getName()).isEqualTo("수정됨");
        assertThat(tag.getColor()).isEqualTo("#FFFFFF");
    }

    @Test @DisplayName("updateInfo: null 이름이면 기존 유지")
    void updateInfo_nullName() {
        Tag tag = Tag.create("원본", "#000000");
        tag.updateInfo(null, "#AABBCC");
        assertThat(tag.getName()).isEqualTo("원본");
    }

    @Test @DisplayName("updateInfo: null 색상이면 기존 유지")
    void updateInfo_nullColor() {
        Tag tag = Tag.create("원본", "#000000");
        tag.updateInfo("새이름", null);
        assertThat(tag.getColor()).isEqualTo("#000000");
    }

    @Test @DisplayName("updateInfo: 잘못된 색상이면 예외")
    void updateInfo_invalidColor() {
        Tag tag = Tag.create("원본", "#000000");
        assertThatThrownBy(() -> tag.updateInfo(null, "invalid"))
                .isInstanceOf(ProductInvariantViolationException.class);
    }

    @Test @DisplayName("setter: id 설정")
    void setter_id() {
        Tag tag = new Tag();
        tag.assignId(42L);
        assertThat(tag.getId()).isEqualTo(42L);
    }
}
