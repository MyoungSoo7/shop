package github.lms.lemuel.bulkorder.domain;

import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 셀 값 검증 규칙 — <b>enum-Strategy</b>.
 *
 * <p>레거시 커머스(ssgb2e-front {@code OrderMultiServiceImpl.orderMultiValid})는 검증 종류를
 * {@code item_validate_type} 컬럼의 문자열 "1"~"5" 로 두고 서비스에서 if-else 로 분기했다.
 * 규칙이 DB 에 있다는 아이디어는 옳지만, "3 이 뭐였더라"를 코드에서 다시 찾아야 하고 새 규칙을
 * 넣을 때마다 그 if-else 사슬을 건드려야 한다.
 *
 * <p>여기서는 <b>규칙 종류를 타입으로</b> 올린다. DB 에는 이름(NUMERIC/PHONE/…)이 들어가고
 * 판정은 각 상수가 직접 한다 — 새 규칙은 상수 하나 추가로 끝나고, 분기문은 존재하지 않는다.
 *
 * <p>공통 규칙: <b>빈 값은 어떤 형식 검사도 하지 않는다.</b> "비었다"는 필수 여부가 판단할 문제이지
 * 형식이 판단할 문제가 아니다. 둘을 섞으면 선택 항목을 비웠다고 형식 오류가 뜬다.
 */
public enum BulkOrderValidationType {

    /** 형식 제약 없음(길이·필수만 적용). */
    NONE {
        @Override
        String check(String columnName, String value, String option) {
            return null;
        }
    },

    /** 한글·영문·숫자만. 특수문자로 인한 다운스트림 파싱 사고를 막는다. */
    ALNUM {
        private final Pattern pattern = Pattern.compile("^[ㄱ-ㅎ가-힣a-zA-Z0-9 ]*$");

        @Override
        String check(String columnName, String value, String option) {
            return pattern.matcher(value).matches() ? null
                    : columnName + "은(는) 문자/숫자로 입력해 주세요.";
        }
    },

    /** 숫자만. */
    NUMERIC {
        private final Pattern pattern = Pattern.compile("^[0-9]+$");

        @Override
        String check(String columnName, String value, String option) {
            return pattern.matcher(value).matches() ? null
                    : columnName + "은(는) 숫자로 입력해 주세요.";
        }
    },

    /** 허용값 목록(option 은 쉼표 구분). */
    ENUM {
        @Override
        String check(String columnName, String value, String option) {
            if (option == null || option.isBlank()) {
                // 목록 없는 ENUM 은 설정 실수다. 조용히 통과시키면 검증이 있다고 믿는 채로 뚫린다.
                return columnName + " 허용값 목록이 설정되지 않았습니다.";
            }
            boolean allowed = Arrays.stream(option.split(","))
                    .map(String::trim)
                    .anyMatch(value::equals);
            return allowed ? null : columnName + "은(는) " + option + " 중에서 입력해 주세요.";
        }
    },

    /** 휴대폰번호. 하이픈은 허용하되 판정 전에 걷어낸다. */
    PHONE {
        private final Pattern pattern = Pattern.compile("^01[016789]\\d{7,8}$");

        @Override
        String check(String columnName, String value, String option) {
            return pattern.matcher(value.replaceAll("\\D", "")).matches() ? null
                    : columnName + " 형식이 올바르지 않습니다.";
        }
    },

    /** 이메일. */
    EMAIL {
        private final Pattern pattern =
                Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

        @Override
        String check(String columnName, String value, String option) {
            return pattern.matcher(value).matches() ? null
                    : columnName + " 형식이 올바르지 않습니다.";
        }
    };

    /** 형식 판정 본체 — 빈 값이 아닌 것이 보장된 상태로 호출된다. */
    abstract String check(String columnName, String value, String option);

    /**
     * 형식 검증. 빈 값은 무조건 통과시킨다(필수 여부는 {@link BulkOrderColumnSpec} 이 본다).
     *
     * @return 오류 메시지, 통과하면 {@code null}
     */
    public String validate(String columnName, String value, String option) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return check(columnName, value.trim(), option);
    }

    /** DB 에 저장된 이름을 읽는다. 미상값은 NONE — 알 수 없는 규칙으로 행을 전부 막지 않는다. */
    public static BulkOrderValidationType fromStorage(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NONE;
        }
    }
}
