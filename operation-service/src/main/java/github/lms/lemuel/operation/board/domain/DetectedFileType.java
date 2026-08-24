package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 서버가 <b>파일 내용(매직바이트)</b>을 보고 판정한 실제 형식.
 *
 * <p>업로드 요청이 들고 오는 파일명·Content-Type 은 전부 클라이언트가 정하는 값이라 신뢰할 수
 * 없다. 이 record 는 "바이트가 실제로 무엇인가"만 담고, 그 판정은 어댑터
 * ({@code DetectFileTypePort})가 한다 — 매직바이트 표는 바깥 세상의 지식이다.
 *
 * <p><b>별칭이 필요한 이유</b>: 매직바이트는 확장자보다 거칠다. JPEG 은 {@code .jpg} 와
 * {@code .jpeg} 두 이름을 쓰고, {@code .docx}·{@code .xlsx} 는 파일 앞부분이 그냥 ZIP 이다.
 * 별칭 없이 "판정 == 선언"만 보면 <b>정상 파일이 무더기로 거절</b>된다 — 그러면 사람들은
 * 검사를 끄자고 하게 되고, 그게 진짜 사고로 이어진다.
 *
 * <p>인식하지 못한 형식은 {@link #unknown()} 이다. 모르는 파일을 통과시키지 않는 이유:
 * 서버가 무엇인지 모르는 바이트를 브라우저는 추측해서 실행할 수 있다.
 */
public record DetectedFileType(String extension, String contentType, boolean image, Set<String> aliases) {

    private static final DetectedFileType UNKNOWN = new DetectedFileType(null, null, false, Set.of());

    public static DetectedFileType unknown() {
        return UNKNOWN;
    }

    public static DetectedFileType of(String extension, String contentType, boolean image, String... aliases) {
        if (extension == null || extension.isBlank()) {
            throw new BoardInvariantViolationException("판정된 확장자는 필수입니다.");
        }
        if (contentType == null || contentType.isBlank()) {
            throw new BoardInvariantViolationException("판정된 콘텐츠 타입은 필수입니다.");
        }
        String canonical = extension.trim().toLowerCase(Locale.ROOT);
        Set<String> names = new LinkedHashSet<>();
        names.add(canonical);
        for (String alias : aliases) {
            if (alias != null && !alias.isBlank()) {
                names.add(alias.trim().toLowerCase(Locale.ROOT));
            }
        }
        return new DetectedFileType(canonical, contentType, image, Set.copyOf(names));
    }

    public boolean isUnknown() {
        return extension == null;
    }

    /** 선언된 확장자가 이 형식이 쓸 수 있는 이름인가. */
    public boolean matches(String declaredExtension) {
        return declaredExtension != null && aliases.contains(declaredExtension.toLowerCase(Locale.ROOT));
    }

    public BoardAttachmentKind kind() {
        return image ? BoardAttachmentKind.IMAGE : BoardAttachmentKind.FILE;
    }
}
