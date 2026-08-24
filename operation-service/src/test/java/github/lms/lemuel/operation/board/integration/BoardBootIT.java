package github.lms.lemuel.operation.board.integration;

import github.lms.lemuel.OperationServiceApplication;
import github.lms.lemuel.operation.board.application.port.out.LoadBoardDefinitionPort;
import github.lms.lemuel.operation.board.domain.BoardDefinition;
import github.lms.lemuel.operation.board.domain.BoardSkin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * board-service 부팅 IT — 실 Flyway 체인 + Hibernate {@code ddl-auto: validate}.
 *
 * <p><b>이 IT 가 없으면 단위 테스트는 전부 초록인데 기동이 깨진다.</b> 마이그레이션(DDL)과 JPA
 * 매핑은 서로를 모르는 두 파일이라, 컬럼 이름·타입·NOT NULL 이 어긋나도 목(mock) 기반 테스트는
 * 아무것도 눈치채지 못한다. 여기서는 실 PostgreSQL 에 V1~V3 을 전부 적용하고 컨텍스트를 띄우는
 * 것 자체가 어서션이다.
 *
 * <p>Docker 가 없으면 통째로 skip 된다 — 그때 이 파일은 "통과"가 아니라 "미실행"이다.
 * 게이트 출력을 인용할 때 그 차이를 뭉개지 말 것.
 */
@SpringBootTest(
        classes = OperationServiceApplication.class,
        properties = {
                "app.jwt.secret=integration-test-secret-key-32-bytes-min-OK"
        }
)
@Testcontainers
@EnabledIf(value = "isDockerAvailable", disabledReason = "Docker is not available")
class BoardBootIT {

    static boolean isDockerAvailable() {
        try {
            DockerClientFactory.instance().client();
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("board_test").withUsername("test").withPassword("test");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("POSTGRES_USER", POSTGRES::getUsername);
        registry.add("POSTGRES_PASSWORD", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    LoadBoardDefinitionPort loadBoardDefinitionPort;

    @Test
    @DisplayName("Flyway 체인 + ddl-auto:validate 로 컨텍스트가 뜬다 — 테이블 3종 존재")
    void flywayCreatesTablesAndEntitiesValidate() {
        assertThat(tableExists("board_definitions")).isTrue();
        assertThat(tableExists("board_posts")).isTrue();
        assertThat(tableExists("board_attachments")).isTrue();
    }

    @Test
    @DisplayName("게시판 테이블은 opslab 이 아니라 board 스키마에 있다 — 슬라이스 데이터 경계 유지")
    void ownsItsOwnSchema() {
        // 흡수 전에는 "opslab 테이블 0"이 어서션이었지만, operation 통합 후 opslab 은 관제 코어의
        // 소유다. 지금 지켜야 하는 불변식은 "게시판 테이블이 opslab 로 새지 않는다"이다.
        Integer boardTablesInOpslab = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'opslab' AND table_name LIKE 'board\\_%'
                """, Integer.class);

        assertThat(boardTablesInOpslab).isZero();
    }

    @Test
    @DisplayName("board 마이그레이션 4종이 operation Flyway 체인에 순서대로 올라 있다")
    void flywayRoster() {
        // 히스토리 테이블은 flyway 기본 스키마(opslab)에 하나다 — board 스키마엔 히스토리가 없다.
        List<String> versions = jdbc.queryForList("""
                SELECT version FROM opslab.flyway_schema_history
                 WHERE version LIKE '20260825100%'
                 ORDER BY installed_rank
                """, String.class);

        assertThat(versions).containsExactly(
                "20260825100000", "20260825100100", "20260825100200", "20260825100300");
    }

    @Test
    @DisplayName("시드 게시판이 도메인 불변식을 지킨다 — GALLERY 는 첨부가 켜져 있어야 한다")
    void seedSatisfiesDomainInvariants() {
        // rehydrate 는 저장값을 재검증하지 않는다(정책이 강화돼도 조회가 죽지 않도록). 그래서
        // 시드가 불변식을 어겨도 조회는 조용히 성공하고, 그 게시판에 글을 쓸 때야 터진다.
        // 그 간극을 여기서 막는다.
        List<BoardDefinition> seeded = loadBoardDefinitionPort.findAll();
        assertThat(seeded).extracting(BoardDefinition::getBoardKey)
                .contains("notice", "gallery");

        seeded.stream()
                .filter(definition -> definition.getSkin() == BoardSkin.GALLERY)
                .forEach(definition -> assertThat(definition.getAttachmentPolicy().isEnabled())
                        .as("GALLERY 게시판 '%s' 는 첨부가 켜져 있어야 한다", definition.getBoardKey())
                        .isTrue());

        seeded.stream()
                .filter(definition -> definition.getSkin() == BoardSkin.QNA)
                .forEach(definition -> assertThat(definition.getContentPolicy().isCommentsEnabled())
                        .as("QNA 게시판 '%s' 는 댓글이 켜져 있어야 한다", definition.getBoardKey())
                        .isTrue());
    }

    @Test
    @DisplayName("CHECK 제약이 렌더되지 않는 스킨을 막는다 — 수기 INSERT·복구 스크립트 대비")
    void checkConstraintsGuardEnumColumns() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO board.board_definitions
                    (board_key, name, skin, content_format, comments_enabled, secret_enabled,
                     attachments_enabled, max_attachment_count, max_attachment_size_kb, active)
                VALUES ('broken', '깨진 게시판', 'CAROUSEL', 'TEXT', true, false, false, 0, 0, true)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("게시판 키는 UNIQUE 다 — 응용 계층의 409 뒤에 DB 제약이 받쳐 준다")
    void boardKeyIsUnique() {
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO board.board_definitions
                    (board_key, name, skin, content_format, comments_enabled, secret_enabled,
                     attachments_enabled, max_attachment_count, max_attachment_size_kb, active)
                VALUES ('notice', '중복 공지', 'LIST', 'TEXT', true, false, false, 0, 0, true)
                """))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private boolean tableExists(String table) {
        Integer count = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'board' AND table_name = ?
                """, Integer.class, table);
        return count != null && count > 0;
    }
}
