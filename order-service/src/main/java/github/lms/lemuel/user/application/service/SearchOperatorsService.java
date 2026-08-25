package github.lms.lemuel.user.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.user.application.port.in.SearchOperatorsUseCase;
import github.lms.lemuel.user.application.port.out.SearchOperatorsPort;
import github.lms.lemuel.user.application.port.out.SearchOperatorsPort.OperatorCriteria;
import github.lms.lemuel.user.domain.UserRole;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 운영자 계정 콘솔 조회 서비스 — 조회 조건 정규화와 <b>대상 역할 고정</b>이 이 계층의 일이다.
 *
 * <p><b>OPERATOR_ROLES 를 여기서 정하는 이유</b>: 어댑터가 역할 목록을 스스로 정하면 화면이
 * 파라미터로 임의 역할을 밀어 넣어 전 회원 로그인 상태를 읽는 통로가 된다. 요청이 고를 수 있는
 * 것은 "둘 중 하나로 좁힐지"뿐이고, 모르는 값이 오면 <b>둘 다</b>로 떨어뜨린다 — 오타 하나가
 * 조건을 지워 범위를 넓히는 일은 없어야 한다.
 *
 * <p><b>{@code now} 를 여기서 한 번만 읽는 이유</b>: 잠금 판정은 시각 비교다. 목록 조회와 건수
 * 조회가 각자 시계를 보면 같은 요청 안에서 "잠긴 계정 3건"이라 해 놓고 목록에는 2건이 나오는
 * 상태가 생긴다. {@link Clock} 을 주입받는 것은 그 시간 규칙을 테스트에서 재현하기 위해서다
 * ({@code LoginService} 와 같은 규약).
 */
@Service
public class SearchOperatorsService implements SearchOperatorsUseCase {

    /** 이 콘솔이 다루는 역할. 요청이 바꿀 수 없다. */
    public static final List<String> OPERATOR_ROLES =
            List.of(UserRole.ADMIN.name(), UserRole.MANAGER.name());

    /** 한 페이지 최대 인원. */
    public static final int MAX_PAGE_SIZE = 200;

    /** 한 페이지 기본 인원. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** CSV 내보내기 최대 행수. 넘치면 잘라내되 잘렸다는 사실을 응답에 실어 보낸다. */
    public static final int MAX_EXPORT_ROWS = 5_000;

    private final SearchOperatorsPort searchOperatorsPort;
    private final Clock clock;

    public SearchOperatorsService(SearchOperatorsPort searchOperatorsPort, Clock clock) {
        this.searchOperatorsPort = searchOperatorsPort;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public OperatorPage search(OperatorQuery query) {
        OperatorCriteria criteria = toCriteria(query);
        int page = Math.max(query.page(), 0);
        int size = normalizeSize(query.size());

        long total = searchOperatorsPort.count(criteria);
        List<OperatorSummary> content = total == 0
                ? List.of()
                : searchOperatorsPort.search(criteria, page, size);

        int totalPages = (int) ((total + size - 1) / size);
        return new OperatorPage(content, page, size, total, totalPages);
    }

    /**
     * 내보내기는 <b>감사에 남긴다</b>. 회원 명부 내보내기와 같은 판단이다 — 파일은 우리 통제를
     * 벗어난다. 여기 실리는 것은 "권한 있는 계정 목록과 그 각각이 마지막으로 언제 쓰였는가"라,
     * 공격자 입장에서는 어느 계정을 노려야 아무도 눈치채지 못하는지가 적힌 문서다.
     */
    @Override
    @Transactional(readOnly = true)
    @Auditable(
            action = AuditAction.OPERATOR_LIST_EXPORTED,
            resourceType = "User",
            detail = "{'keyword': #p0.keyword(), 'role': #p0.role(), 'lockedOnly': #p0.lockedOnly(),"
                    + " 'idleDays': #p0.idleDays(), 'neverLoggedIn': #p0.neverLoggedIn(),"
                    + " 'rowCount': #result == null ? null : #result.rows().size(),"
                    + " 'truncated': #result == null ? null : #result.truncated()}"
    )
    public OperatorExport export(OperatorQuery query) {
        OperatorCriteria criteria = toCriteria(query);
        long total = searchOperatorsPort.count(criteria);
        if (total == 0) {
            return new OperatorExport(List.of(), false, 0);
        }

        int wanted = (int) Math.min(total, MAX_EXPORT_ROWS);
        List<OperatorSummary> rows = new ArrayList<>(wanted);
        for (int page = 0; rows.size() < wanted; page++) {
            List<OperatorSummary> chunk = searchOperatorsPort.search(criteria, page, MAX_PAGE_SIZE);
            if (chunk.isEmpty()) {
                // 조회 중 대상이 줄어든 경우(역할 강등 등). 무한 루프를 막는다.
                break;
            }
            for (OperatorSummary row : chunk) {
                if (rows.size() == wanted) {
                    break;
                }
                rows.add(row);
            }
        }

        return new OperatorExport(rows, total > MAX_EXPORT_ROWS, total);
    }

    private OperatorCriteria toCriteria(OperatorQuery query) {
        LocalDateTime now = LocalDateTime.now(clock);

        return new OperatorCriteria(
                resolveRoles(query.role()),
                blankToNull(query.keyword()),
                query.lockedOnly(),
                resolveIdleBefore(query.idleDays(), now),
                query.neverLoggedIn(),
                now);
    }

    /**
     * 요청의 역할 파라미터를 대상 목록으로 옮긴다.
     *
     * <p>ADMIN/MANAGER 가 아닌 값은 <b>전부 무시하고 둘 다</b>로 떨어진다. 여기서 요청 값을
     * 그대로 신뢰하면 {@code ?role=USER} 한 번으로 이 화면이 전 회원 로그인 상태 조회가 된다.
     */
    private static List<String> resolveRoles(String role) {
        if (role == null || role.isBlank()) {
            return OPERATOR_ROLES;
        }
        String normalized = role.trim().toUpperCase(java.util.Locale.ROOT);
        return OPERATOR_ROLES.contains(normalized) ? List.of(normalized) : OPERATOR_ROLES;
    }

    /** 0 이하 일수는 조건 미적용으로 흘린다 — {@code idleDays=0} 은 "전부"라 조건이 아니다. */
    private static LocalDateTime resolveIdleBefore(Integer idleDays, LocalDateTime now) {
        if (idleDays == null || idleDays <= 0) {
            return null;
        }
        return now.minusDays(idleDays);
    }

    private static int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(size, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
