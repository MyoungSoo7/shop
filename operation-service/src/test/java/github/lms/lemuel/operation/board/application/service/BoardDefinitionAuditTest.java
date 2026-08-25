package github.lms.lemuel.operation.board.application.service;

import github.lms.lemuel.common.audit.application.Auditable;
import github.lms.lemuel.common.audit.domain.AuditAction;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.AccessSpec;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.AttachmentSpec;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.ContentSpec;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.CreateBoardCommand;
import github.lms.lemuel.operation.board.application.port.in.ManageBoardUseCase.UpdateBoardCommand;
import github.lms.lemuel.operation.board.domain.BoardAccessPolicy;
import github.lms.lemuel.operation.board.domain.BoardAttachmentPolicy;
import github.lms.lemuel.operation.board.domain.BoardContentFormat;
import github.lms.lemuel.operation.board.domain.BoardContentPolicy;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 게시판 관리 조작이 감사에 남는지 지킨다.
 *
 * <p><b>왜 이 테스트가 따로 있나</b>: 게시판 생성·수정·닫기·열기·삭제는 배포된 링크와 메뉴를
 * 한 번에 죽이는 조작인데, 지금까지 누가 했는지 아무 데도 남지 않았다. 애노테이션 하나가
 * 조용히 빠지면 그 상태로 되돌아가고 <b>테스트는 전부 초록으로 남는다</b> — 감사 누락은 기능을
 * 깨뜨리지 않기 때문이다. 그래서 여기서 명시적으로 막는다.
 *
 * <p>애노테이션이 붙었는지만 보지 않고 <b>SpEL 을 실제 객체에 평가</b>한다. 표현식 오타는
 * {@code AuditAspect} 가 삼켜서 detail 을 {@code auditExpressionError} 로 바꿔 버릴 뿐 예외가 나지
 * 않는다 — 붙어 있는데 아무것도 못 남기는 상태가 조용히 성립한다. 평가 변수 이름(#p0/#result)은
 * 애스펙트가 세우는 규약을 여기서 그대로 재현한 것이다(그 규약 자체는 shared-common 의
 * {@code AuditApplicationTest} 가 지킨다).
 */
class BoardDefinitionAuditTest {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    @Test
    @DisplayName("ManageBoardUseCase 의 쓰기 5개는 전부 @Auditable 이 붙어 있다")
    void everyWriteMethodIsAudited() throws Exception {
        for (Method port : ManageBoardUseCase.class.getDeclaredMethods()) {
            Method impl = BoardDefinitionService.class.getMethod(port.getName(), port.getParameterTypes());
            assertThat(impl.getAnnotation(Auditable.class))
                    .as("%s 에 @Auditable 이 없다 — 이 조작은 누가 했는지 남지 않는다", port.getName())
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("액션·리소스타입이 조작별로 정확히 매핑된다")
    void actionsMapToOperations() throws Exception {
        assertThat(auditable("create", CreateBoardCommand.class).action()).isEqualTo(AuditAction.BOARD_CREATED);
        assertThat(auditable("update", Long.class, UpdateBoardCommand.class).action()).isEqualTo(AuditAction.BOARD_UPDATED);
        // 닫기와 삭제가 같은 액션이면 되돌릴 수 있는 조작인지를 상세를 열어봐야 알게 된다.
        assertThat(auditable("deactivate", Long.class).action()).isEqualTo(AuditAction.BOARD_DEACTIVATED);
        assertThat(auditable("activate", Long.class).action()).isEqualTo(AuditAction.BOARD_ACTIVATED);
        assertThat(auditable("delete", Long.class).action()).isEqualTo(AuditAction.BOARD_DELETED);

        for (Method port : ManageBoardUseCase.class.getDeclaredMethods()) {
            Method impl = BoardDefinitionService.class.getMethod(port.getName(), port.getParameterTypes());
            assertThat(impl.getAnnotation(Auditable.class).resourceType()).isEqualTo("Board");
        }
    }

    @Test
    @DisplayName("생성 감사는 성공 시 만들어진 게시판 id 와 요청 내용을 남긴다")
    void createRecordsIdAndRequest() throws Exception {
        Auditable a = auditable("create", CreateBoardCommand.class);
        BoardDefinition saved = definition();

        assertThat(evalString(a.resourceId(), context(new Object[]{createCommand()}, saved))).isEqualTo("7");
        assertThat(evalMap(a.detail(), context(new Object[]{createCommand()}, saved)))
                .containsEntry("boardKey", "notice")
                .containsEntry("name", "공지사항")
                .containsEntry("skin", "LIST");
    }

    @Test
    @DisplayName("생성이 키 중복으로 실패해도 무엇을 만들려 했는지는 남는다")
    void createRecordsRequestEvenWhenResultIsNull() throws Exception {
        Auditable a = auditable("create", CreateBoardCommand.class);
        StandardEvaluationContext ctx = context(new Object[]{createCommand()}, null);

        // 실패 경로에서 #result 는 null 이다. 널가드가 빠져 있으면 여기서 터지고, 운영에서는
        // 애스펙트가 그 예외를 삼켜 상세가 통째로 사라진다.
        assertThat(evalString(a.resourceId(), ctx)).isNull();
        assertThat(evalMap(a.detail(), ctx)).containsEntry("boardKey", "notice");
    }

    @Test
    @DisplayName("수정 감사는 대상 id 와 바뀐 이름·스킨을 남긴다")
    void updateRecordsTargetAndChange() throws Exception {
        Auditable a = auditable("update", Long.class, UpdateBoardCommand.class);
        UpdateBoardCommand command = new UpdateBoardCommand("자료실", "설명", BoardSkin.GALLERY,
                contentSpec(), attachmentSpec(), accessSpec());
        StandardEvaluationContext ctx = context(new Object[]{7L, command}, definition());

        assertThat(evalString(a.resourceId(), ctx)).isEqualTo("7");
        assertThat(evalMap(a.detail(), ctx))
                .containsEntry("name", "자료실")
                .containsEntry("skin", "GALLERY");
    }

    @Test
    @DisplayName("닫기·열기 감사는 어떤 게시판인지를 키로 남긴다")
    void deactivateAndActivateRecordBoardKey() throws Exception {
        for (String name : List.of("deactivate", "activate")) {
            Auditable a = auditable(name, Long.class);
            StandardEvaluationContext ctx = context(new Object[]{7L}, definition());

            assertThat(evalString(a.resourceId(), ctx)).isEqualTo("7");
            assertThat(evalMap(a.detail(), ctx)).as(name).containsEntry("boardKey", "notice");
        }
    }

    @Test
    @DisplayName("삭제 감사는 반환이 void 라도 대상 id 를 남긴다")
    void deleteRecordsTargetId() throws Exception {
        Auditable a = auditable("delete", Long.class);
        StandardEvaluationContext ctx = context(new Object[]{7L}, null);

        assertThat(evalString(a.resourceId(), ctx)).isEqualTo("7");
        assertThat(evalMap(a.detail(), ctx)).containsEntry("boardId", 7L);
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private static Auditable auditable(String method, Class<?>... parameterTypes) throws Exception {
        return BoardDefinitionService.class.getMethod(method, parameterTypes).getAnnotation(Auditable.class);
    }

    /** AuditAspect 가 세우는 변수 규약(#p0..#pN, #result)을 그대로 재현한다. */
    private static StandardEvaluationContext context(Object[] args, Object result) {
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        ctx.setVariable("result", result);
        for (int i = 0; i < args.length; i++) {
            ctx.setVariable("p" + i, args[i]);
            ctx.setVariable("a" + i, args[i]);
        }
        return ctx;
    }

    private static String evalString(String expression, StandardEvaluationContext ctx) {
        Object value = PARSER.parseExpression(expression).getValue(ctx);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> evalMap(String expression, StandardEvaluationContext ctx) {
        return (Map<String, Object>) PARSER.parseExpression(expression).getValue(ctx);
    }

    private static CreateBoardCommand createCommand() {
        return new CreateBoardCommand("notice", "공지사항", "설명", BoardSkin.LIST,
                contentSpec(), attachmentSpec(), accessSpec());
    }

    /** 저장 후 상태 — 감사가 읽는 것은 id 와 키뿐이라 나머지는 최소로 채운다. */
    private static BoardDefinition definition() {
        return BoardDefinition.rehydrate(7L, "notice", "공지사항", null, BoardSkin.LIST,
                BoardContentPolicy.rehydrate(BoardContentFormat.TEXT, true, false, null),
                BoardAttachmentPolicy.disabled(),
                BoardAccessPolicy.rehydrate(List.of(), List.of("ADMIN"), List.of("USER"), List.of("ADMIN")),
                true,
                OffsetDateTime.of(2026, 8, 25, 0, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 8, 25, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private static ContentSpec contentSpec() {
        return new ContentSpec(BoardContentFormat.HTML, true, false, null);
    }

    private static AttachmentSpec attachmentSpec() {
        return new AttachmentSpec(false, 0, 0, List.of());
    }

    private static AccessSpec accessSpec() {
        return new AccessSpec(List.of("USER"), List.of("ADMIN"), List.of("USER"), List.of("ADMIN"));
    }
}
