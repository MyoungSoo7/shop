package github.lms.lemuel.operation.board.domain;

import github.lms.lemuel.operation.board.domain.exception.BoardInvariantViolationException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 첨부 정책 — 첨부 허용 여부와 그 한계(개수·크기·확장자).
 *
 * <p>비활성 상태는 {@link #disabled()} 하나로 정규화한다. "첨부 불가인데 최대 5개" 같은 값이
 * 저장되면 나중에 첨부를 켤 때 어떤 값이 진짜인지 알 수 없다 — 꺼진 정책은 항상 0/0/빈집합이다.
 *
 * <p>확장자는 <b>표시용 1차 방어</b>다. 실제 검증은 Phase 3 의 매직바이트 검사가 담당한다 —
 * 확장자만 믿으면 {@code shell.php} 를 {@code shell.jpg} 로 바꾼 업로드를 그대로 통과시킨다.
 */
public final class BoardAttachmentPolicy {

    /** 개수 상한. 이보다 크면 목록 렌더·스토리지 정리 비용이 게시판 하나에 몰린다. */
    private static final int MAX_COUNT_LIMIT = 20;
    /** 파일 크기 상한 20MB. 게시판은 파일 서버가 아니다. */
    private static final int MAX_SIZE_KB_LIMIT = 20_480;

    private final boolean enabled;
    private final int maxCount;
    private final int maxSizeKb;
    private final Set<String> allowedExtensions;

    private BoardAttachmentPolicy(boolean enabled, int maxCount, int maxSizeKb, Set<String> allowedExtensions) {
        this.enabled = enabled;
        this.maxCount = maxCount;
        this.maxSizeKb = maxSizeKb;
        this.allowedExtensions = allowedExtensions;
    }

    public static BoardAttachmentPolicy disabled() {
        return new BoardAttachmentPolicy(false, 0, 0, Set.of());
    }

    public static BoardAttachmentPolicy enabled(int maxCount, int maxSizeKb, Collection<String> allowedExtensions) {
        if (maxCount < 1 || maxCount > MAX_COUNT_LIMIT) {
            throw new BoardInvariantViolationException(
                    "첨부 최대 개수는 1~" + MAX_COUNT_LIMIT + " 사이여야 합니다: " + maxCount);
        }
        if (maxSizeKb < 1 || maxSizeKb > MAX_SIZE_KB_LIMIT) {
            throw new BoardInvariantViolationException(
                    "첨부 최대 크기(KB)는 1~" + MAX_SIZE_KB_LIMIT + " 사이여야 합니다: " + maxSizeKb);
        }
        Set<String> extensions = normalizeExtensions(allowedExtensions);
        if (extensions.isEmpty()) {
            throw new BoardInvariantViolationException(
                    "첨부를 허용하려면 허용 확장자를 최소 하나 지정해야 합니다. 전체 허용은 지원하지 않습니다.");
        }
        return new BoardAttachmentPolicy(true, maxCount, maxSizeKb, extensions);
    }

    /** 영속 레코드 복원 — 저장값 재검증 금지(사유는 {@link BoardAccessPolicy#rehydrate} 와 동일). */
    public static BoardAttachmentPolicy rehydrate(boolean enabled, int maxCount, int maxSizeKb,
                                                  Collection<String> allowedExtensions) {
        return new BoardAttachmentPolicy(enabled, maxCount, maxSizeKb, normalizeExtensions(allowedExtensions));
    }

    private static Set<String> normalizeExtensions(Collection<String> extensions) {
        if (extensions == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : extensions) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            // '.JPG' · 'jpg' 어느 쪽으로 들어와도 같은 값이 되도록 점을 떼고 소문자로 접는다.
            String token = raw.trim().toLowerCase(Locale.ROOT);
            if (token.startsWith(".")) {
                token = token.substring(1);
            }
            if (token.isEmpty()) {
                continue;
            }
            if (!token.matches("[a-z0-9]{1,8}")) {
                throw new BoardInvariantViolationException("확장자 형식이 올바르지 않습니다: " + raw);
            }
            normalized.add(token);
        }
        return Set.copyOf(normalized);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 이 확장자를 이 게시판이 받아들이는가. 첨부가 꺼져 있으면 무조건 거부다. */
    public boolean permits(String extension) {
        if (!enabled || extension == null || extension.isBlank()) {
            return false;
        }
        String token = extension.trim().toLowerCase(Locale.ROOT);
        if (token.startsWith(".")) {
            token = token.substring(1);
        }
        return allowedExtensions.contains(token);
    }

    public int maxCount() {
        return maxCount;
    }

    public int maxSizeKb() {
        return maxSizeKb;
    }

    public Set<String> allowedExtensions() {
        return allowedExtensions;
    }
}
