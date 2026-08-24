package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

import java.util.Locale;
import java.util.Set;

/**
 * 업로드 한 건 — <b>클라이언트가 주장한 것</b>과 <b>서버가 판정한 것</b>을 함께 든다.
 *
 * <p>둘을 나란히 두는 것이 이 VO 의 존재 이유다. 첨부 사고는 거의 전부 "선언과 실제가 다르다"에서
 * 오는데, 한쪽만 들고 다니면 그 차이를 비교할 자리가 없어진다.
 *
 * <p>파일명은 <b>표시용</b>이다. 저장 경로는 서버가 UUID 로 새로 만들므로, 여기 담긴 이름이
 * 디렉터리를 거슬러 올라가는 데 쓰일 일이 없다. 그래도 경로 구분자를 걷어내는 이유는
 * 이 값이 나중에 Content-Disposition 헤더로 나가기 때문이다.
 */
public record AttachmentUpload(String originalName, long sizeBytes, DetectedFileType detectedType) {

    /**
     * 확장자에 관계없이 언제나 막는 형식.
     *
     * <p>SVG 는 이미지처럼 보이지만 실제로는 스크립트를 담을 수 있는 XML 문서다 — 브라우저가
     * {@code <img>} 가 아니라 문서로 열면 그대로 실행된다. 게시판 정책에서 허용해도 받지 않는다.
     * HTML·XML 계열도 같은 이유다.
     */
    private static final Set<String> ALWAYS_BLOCKED = Set.of("svg", "svgz", "html", "htm", "xhtml", "xml", "xht");

    private static final int NAME_MAX_LENGTH = 200;

    public AttachmentUpload {
        originalName = sanitizeName(originalName);
        if (sizeBytes <= 0) {
            throw new BoardInvariantViolationException("빈 파일은 첨부할 수 없습니다.");
        }
        if (detectedType == null) {
            throw new BoardInvariantViolationException("판정 결과는 필수입니다.");
        }
    }

    /**
     * 표시용 파일명 정규화 — 경로 구분자와 제어문자를 걷어낸다.
     *
     * <p>{@code ../../etc/passwd} 같은 이름이 들어와도 마지막 세그먼트만 남는다. 저장 경로는
     * 어차피 서버가 만들지만, 이 이름은 다운로드 헤더로 나가므로 여기서 접어 둔다.
     */
    private static String sanitizeName(String originalName) {
        if (originalName == null || originalName.isBlank()) {
            throw new BoardInvariantViolationException("파일명은 필수입니다.");
        }
        String name = originalName.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // 개행·따옴표는 Content-Disposition 헤더를 쪼갤 수 있다.
        name = name.replaceAll("[\\p{Cntrl}\"]", "").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new BoardInvariantViolationException("파일명이 올바르지 않습니다: " + originalName);
        }
        return name.length() > NAME_MAX_LENGTH ? name.substring(name.length() - NAME_MAX_LENGTH) : name;
    }

    /** 표시용 이름에서 뽑은 확장자(소문자, 점 없음). 없으면 빈 문자열. */
    public String declaredExtension() {
        int dot = originalName.lastIndexOf('.');
        return dot < 0 || dot == originalName.length() - 1
                ? ""
                : originalName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * 이 업로드가 게시판 정책과 실제 내용 양쪽을 통과하는지 검사한다.
     *
     * <p>순서가 곧 방어의 순서다: ① 서버가 형식을 알아볼 수 있는가 → ② 언제나 막는 형식인가
     * → ③ 선언과 실제가 같은가 → ④ 게시판이 허용하는 확장자인가 → ⑤ 크기 한도.
     */
    public void validateAgainst(BoardAttachmentPolicy policy) {
        if (!policy.isEnabled()) {
            throw new BoardInvariantViolationException("이 게시판은 첨부를 받지 않습니다.");
        }
        if (detectedType.isUnknown()) {
            throw new BoardInvariantViolationException(
                    "형식을 알 수 없는 파일은 첨부할 수 없습니다: " + originalName);
        }
        String detected = detectedType.extension();
        if (ALWAYS_BLOCKED.contains(detected) || ALWAYS_BLOCKED.contains(declaredExtension())) {
            throw new BoardInvariantViolationException(
                    "스크립트를 담을 수 있는 형식은 첨부할 수 없습니다: " + originalName);
        }
        if (!detectedType.matches(declaredExtension())) {
            // 확장자만 바꿔 올리는 고전적 우회. 서버가 본 것과 다르면 거절한다
            // (jpg/jpeg, docx/zip 처럼 같은 형식이 쓰는 다른 이름은 별칭으로 통과한다).
            throw new BoardInvariantViolationException(
                    "파일 내용이 확장자와 다릅니다: " + originalName + " (실제 " + detected + ")");
        }
        // 정책은 관리자가 적어 둔 이름(declared)으로 대조하되, 판정된 정규 이름도 함께 본다 —
        // 'jpg' 만 허용한 게시판에 진짜 JPEG 인 photo.jpeg 가 막히면 규칙이 사람을 이긴 셈이 된다.
        if (!policy.permits(declaredExtension()) && !policy.permits(detected)) {
            throw new BoardInvariantViolationException("허용하지 않는 확장자입니다: " + declaredExtension());
        }
        long maxBytes = (long) policy.maxSizeKb() * 1024L;
        if (sizeBytes > maxBytes) {
            throw new BoardInvariantViolationException(
                    "파일이 너무 큽니다: " + sizeBytes + "바이트 (최대 " + maxBytes + ")");
        }
    }

    public BoardAttachmentKind kind() {
        return detectedType.kind();
    }
}
