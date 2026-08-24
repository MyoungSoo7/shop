package github.lms.lemuel.bulkorder.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 메타 주도 셀 검증 — 규칙이 코드가 아니라 데이터라는 것이 이 기능의 요점이다.
 *
 * <p>레거시 커머스(ssgb2e)는 {@code item_validate_type} 을 "1"~"5" 문자열로 두고 서비스에서
 * if-else 로 분기했다. 여기서는 규칙 종류를 타입으로 올려 분기문 자체가 없다.
 */
@DisplayName("대량주문 셀 검증 — 필수 · 길이 · 형식")
class BulkOrderValidationTest {

    private static BulkOrderColumnSpec spec(boolean required, Integer maxLength,
                                            BulkOrderValidationType type, String option) {
        return new BulkOrderColumnSpec(0, "test_code", "테스트항목", required, maxLength, type, option);
    }

    @Nested
    @DisplayName("검사 순서")
    class Order {

        @Test
        @DisplayName("필수가 형식보다 먼저다 — 비었는데 '형식 오류'라고 하면 무엇을 고칠지 모른다")
        void requiredBeatsFormat() {
            String error = spec(true, 10, BulkOrderValidationType.NUMERIC, null).validate("");

            assertThat(error).contains("누락");
        }

        @Test
        @DisplayName("길이가 형식보다 먼저다 — 잘림이 진짜 원인일 때 형식 오류가 그것을 묻는다")
        void lengthBeatsFormat() {
            String error = spec(true, 3, BulkOrderValidationType.NUMERIC, null).validate("abcdef");

            assertThat(error).contains("3글자");
        }

        @Test
        @DisplayName("선택 항목의 빈 값은 어떤 형식 검사도 하지 않는다")
        void optionalEmptyPasses() {
            assertThat(spec(false, 10, BulkOrderValidationType.PHONE, null).validate("")).isNull();
            assertThat(spec(false, 10, BulkOrderValidationType.PHONE, null).validate(null)).isNull();
        }
    }

    @Nested
    @DisplayName("형식 규칙")
    class Formats {

        @ParameterizedTest
        @ValueSource(strings = {"010-1234-5678", "01012345678"})
        void phoneAcceptsHyphenOrNot(String value) {
            assertThat(spec(true, 20, BulkOrderValidationType.PHONE, null).validate(value)).isNull();
        }

        @Test
        void phoneRejectsLandline() {
            assertThat(spec(true, 20, BulkOrderValidationType.PHONE, null).validate("02-123-4567"))
                    .contains("형식");
        }

        @Test
        void numericRejectsLetters() {
            assertThat(spec(true, 10, BulkOrderValidationType.NUMERIC, null).validate("12a"))
                    .contains("숫자");
        }

        @Test
        void alnumAcceptsKorean() {
            assertThat(spec(true, 50, BulkOrderValidationType.ALNUM, null).validate("홍길동 2"))
                    .isNull();
        }

        @Test
        void alnumRejectsSpecialCharacters() {
            assertThat(spec(true, 50, BulkOrderValidationType.ALNUM, null).validate("홍길동<script>"))
                    .contains("문자/숫자");
        }

        @Test
        void enumAcceptsListedValue() {
            assertThat(spec(true, 10, BulkOrderValidationType.ENUM, "일반,빠른").validate("빠른"))
                    .isNull();
        }

        @Test
        void enumRejectsUnlistedValue() {
            assertThat(spec(true, 10, BulkOrderValidationType.ENUM, "일반,빠른").validate("당일"))
                    .contains("일반,빠른");
        }

        @Test
        @DisplayName("허용값 목록이 없는 ENUM 은 설정 실수다 — 조용히 통과시키면 검증이 있다고 믿는 채로 뚫린다")
        void enumWithoutOptionsFailsLoudly() {
            assertThat(spec(true, 10, BulkOrderValidationType.ENUM, null).validate("아무거나"))
                    .contains("허용값 목록");
        }

        @Test
        void emailChecksShape() {
            assertThat(spec(true, 50, BulkOrderValidationType.EMAIL, null).validate("a@b.co")).isNull();
            assertThat(spec(true, 50, BulkOrderValidationType.EMAIL, null).validate("a@b")).contains("형식");
        }

        @Test
        @DisplayName("모르는 규칙 이름은 NONE 으로 읽는다 — 오타 하나로 전 행이 막히지 않게")
        void unknownTypeFallsBackToNone() {
            assertThat(BulkOrderValidationType.fromStorage("REGEXP_V2"))
                    .isEqualTo(BulkOrderValidationType.NONE);
            assertThat(BulkOrderValidationType.fromStorage(null))
                    .isEqualTo(BulkOrderValidationType.NONE);
        }
    }

    @Nested
    @DisplayName("행 단위")
    class Rows {

        private final List<BulkOrderColumnSpec> specs = List.of(
                new BulkOrderColumnSpec(0, "product_id", "상품번호", true, 18,
                        BulkOrderValidationType.NUMERIC, null),
                new BulkOrderColumnSpec(1, "quantity", "수량", true, 6,
                        BulkOrderValidationType.NUMERIC, null),
                new BulkOrderColumnSpec(2, "recipient_phone", "연락처", true, 20,
                        BulkOrderValidationType.PHONE, null));

        @Test
        @DisplayName("오류를 첫 건에서 멈추지 않고 모두 모은다 — 한 번에 다 고칠 수 있게")
        void collectsAllErrors() {
            BulkOrderRow row = BulkOrderRow.uploaded(1, List.of("abc", "", "02-123-4567"));

            assertThat(row.validate(specs)).isFalse();
            assertThat(row.getErrorMessage())
                    .contains("상품번호").contains("수량").contains("연락처");
        }

        @Test
        @DisplayName("어느 칸이 틀렸는지 셀 단위로 남는다 — 화면이 그 칸을 짚을 수 있게")
        void marksFailingCell() {
            BulkOrderRow row = BulkOrderRow.uploaded(1, List.of("100", "2", "02-123-4567"));

            row.validate(specs);

            assertThat(row.getCells().get(0).isValid()).isTrue();
            assertThat(row.getCells().get(1).isValid()).isTrue();
            assertThat(row.getCells().get(2).isValid()).isFalse();
            assertThat(row.getCells().get(2).getErrorMessage()).contains("연락처");
        }

        @Test
        @DisplayName("열이 모자란 행은 '필수 누락'으로 드러난다 — 조용히 통과하지 않는다")
        void missingColumnsSurfaceAsRequired() {
            BulkOrderRow row = BulkOrderRow.uploaded(1, List.of("100"));

            assertThat(row.validate(specs)).isFalse();
            assertThat(row.getErrorMessage()).contains("수량").contains("연락처");
        }

        @Test
        @DisplayName("양식보다 열이 많은 파일은 통과시킨다 — 운영자가 메모 열을 덧붙이는 일이 흔하다")
        void extraColumnsArePermitted() {
            BulkOrderRow row = BulkOrderRow.uploaded(1,
                    List.of("100", "2", "010-1234-5678", "사내 메모"));

            assertThat(row.validate(specs)).isTrue();
        }

        @Test
        @DisplayName("업무 코드로 값을 꺼낸다 — 열 위치가 아니라 의미로 접근한다")
        void readsByItemCode() {
            BulkOrderRow row = BulkOrderRow.uploaded(1, List.of("100", "2", "010-1234-5678"));

            assertThat(row.value(specs, "quantity")).isEqualTo("2");
            assertThat(row.value(specs, "없는코드")).isNull();
        }

        @Test
        @DisplayName("재검증에서 통과하면 지난 오류 메시지가 남지 않는다")
        void revalidationClearsStaleErrors() {
            BulkOrderRow bad = BulkOrderRow.uploaded(1, List.of("abc", "2", "010-1234-5678"));
            bad.validate(specs);
            assertThat(bad.getErrorMessage()).isNotNull();

            BulkOrderRow fixed = BulkOrderRow.uploaded(1, List.of("100", "2", "010-1234-5678"));
            fixed.validate(specs);

            assertThat(fixed.getErrorMessage()).isNull();
            assertThat(fixed.getCells()).allMatch(BulkOrderCell::isValid);
        }
    }
}
