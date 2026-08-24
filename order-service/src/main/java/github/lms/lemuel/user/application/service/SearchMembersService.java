package github.lms.lemuel.user.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.user.application.port.in.SearchMembersUseCase;
import github.lms.lemuel.user.application.port.out.SearchMembersPort;
import github.lms.lemuel.user.application.port.out.SearchMembersPort.MemberCriteria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 회원 콘솔 조회 서비스 — 조회 조건 정규화가 이 계층의 일이다.
 *
 * <p><b>기간에 기본값을 두지 않는 이유</b>(감사 로그와 다른 선택): {@code users} 는 파티션
 * 테이블이 아니고, 운영자가 찾는 회원은 대개 <b>언제 가입했는지 모르는</b> 사람이다.
 * "최근 30일"을 기본으로 깔면 3년 전 가입자를 찾을 때마다 아무것도 안 나오고, 운영자는
 * 그 이유를 알 수 없다. 대신 페이지 크기 상한으로 응답 크기를 지킨다.
 *
 * <p><b>size 상한</b>: 회원 목록은 이메일·이름·연락처를 담은 PII 덩어리다. 상한이 없으면
 * {@code size=100000} 한 번의 호출이 전 회원 개인정보를 한 응답에 실어 나간다.
 */
@Service
public class SearchMembersService implements SearchMembersUseCase {

    /** 한 페이지 최대 인원. */
    public static final int MAX_PAGE_SIZE = 200;

    /** 한 페이지 기본 인원. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /**
     * CSV 내보내기 최대 행수.
     *
     * <p>넘치면 잘라내되 잘렸다는 사실을 응답에 실어 보낸다 — 잘린 줄 모르는 회원 명부가
     * 밖으로 나가면, 받은 쪽은 그것을 전체로 믿는다.
     */
    public static final int MAX_EXPORT_ROWS = 5_000;

    private final SearchMembersPort searchMembersPort;

    public SearchMembersService(SearchMembersPort searchMembersPort) {
        this.searchMembersPort = searchMembersPort;
    }

    @Override
    @Transactional(readOnly = true)
    public MemberPage search(MemberQuery query) {
        MemberCriteria criteria = toCriteria(query);
        int page = Math.max(query.page(), 0);
        int size = normalizeSize(query.size());

        long total = searchMembersPort.count(criteria);
        List<MemberSummary> content = total == 0
                ? List.of()
                : searchMembersPort.search(criteria, page, size);

        int totalPages = (int) ((total + size - 1) / size);
        return new MemberPage(content, page, size, total, totalPages);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MemberStatusCount> countByStatus(MemberQuery query) {
        return searchMembersPort.countByStatus(toCriteria(query));
    }

    /**
     * 내보내기는 <b>감사에 남긴다</b>. 목록을 화면에서 보는 것과 PII 를 파일로 가져가는 것은
     * 같은 무게가 아니다 — 파일은 우리 통제를 벗어나므로, 최소한 누가 언제 무엇을 조건으로
     * 가져갔는지는 남아야 한다.
     */
    @Override
    @Transactional(readOnly = true)
    @Auditable(
            action = AuditAction.MEMBER_LIST_EXPORTED,
            resourceType = "User",
            detail = "{'keyword': #p0.keyword(), 'role': #p0.role() == null ? null : #p0.role().name(),"
                    + " 'status': #p0.status() == null ? null : #p0.status().name(),"
                    + " 'rowCount': #result == null ? null : #result.rows().size(),"
                    + " 'truncated': #result == null ? null : #result.truncated()}"
    )
    public MemberExport export(MemberQuery query) {
        MemberCriteria criteria = toCriteria(query);
        long total = searchMembersPort.count(criteria);
        if (total == 0) {
            return new MemberExport(List.of(), false, 0);
        }

        int wanted = (int) Math.min(total, MAX_EXPORT_ROWS);
        List<MemberSummary> rows = new ArrayList<>(wanted);
        for (int page = 0; rows.size() < wanted; page++) {
            List<MemberSummary> chunk = searchMembersPort.search(criteria, page, MAX_PAGE_SIZE);
            if (chunk.isEmpty()) {
                // 조회 중 데이터가 줄어든 경우(탈퇴 등). 무한 루프를 막는다.
                break;
            }
            for (MemberSummary row : chunk) {
                if (rows.size() == wanted) {
                    break;
                }
                rows.add(row);
            }
        }

        return new MemberExport(rows, total > MAX_EXPORT_ROWS, total);
    }

    /**
     * 화면 질의를 어댑터 조건으로 옮긴다.
     *
     * <p>뒤집힌 가입일 구간은 거부하지 않고 바로잡는다 — 달력에서 순서를 바꿔 고르는 일은
     * 흔하고, 그때 에러를 던지면 운영자는 아무 회원도 못 본다.
     */
    private MemberCriteria toCriteria(MemberQuery query) {
        LocalDate from = query.joinedFrom();
        LocalDate to = query.joinedTo();
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }

        return new MemberCriteria(
                blankToNull(query.keyword()),
                query.role() != null ? query.role().name() : null,
                query.status() != null ? query.status().name() : null,
                query.active(),
                from != null ? from.atStartOfDay() : null,
                to != null ? to.plusDays(1).atStartOfDay() : null);
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
