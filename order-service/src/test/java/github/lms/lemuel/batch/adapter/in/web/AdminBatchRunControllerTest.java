package github.lms.lemuel.batch.adapter.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import github.lms.lemuel.batch.adapter.out.persistence.BatchRunHistoryJpaEntity;
import github.lms.lemuel.batch.adapter.out.persistence.BatchRunHistoryJpaRepository;
import github.lms.lemuel.batch.application.BatchRerunService;
import github.lms.lemuel.batch.application.port.in.RerunnableBatch;
import github.lms.lemuel.batch.application.port.in.BatchRunOutcome;
import github.lms.lemuel.batch.domain.BatchRunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 배치 실행 원장 콘솔.
 *
 * <p>이 콘솔이 답해야 하는 질문은 "무엇이 돌았나" 가 아니라 <b>"마지막 성공이 언제인가"</b> 다.
 * ShedLock 은 락을 잡은 사실만 적지 결과를 적지 않으므로, 매일 도는 배치가 매일 실패해도
 * 지금까지는 아무 데도 안 남았다. 그래서 여기서 지키는 것은 세 가지다 —
 * 페이지 크기 상한, 필터가 실제로 저장소까지 내려가는 것, 그리고 재실행의 dry-run 기본값.
 */
class AdminBatchRunControllerTest {

    private BatchRunHistoryJpaRepository repository;
    private BatchRerunService rerunService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        repository = mock(BatchRunHistoryJpaRepository.class);
        rerunService = mock(BatchRerunService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminBatchRunController(repository, rerunService))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        // Boot 이 실제로 쓰는 설정과 맞춘다. 이걸 끄지 않으면 날짜가 "2026-09-01" 이
                        // 아니라 [2026,9,1] 배열로 나가고, 그러면 이 테스트는 운영과 다른 계약을 검증한다.
                        new ObjectMapper().findAndRegisterModules()
                                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
                .build();
    }

    /** 엔티티는 생성자가 패키지 밖으로 열려 있지 않다. 컨트롤러가 보는 것은 getter 뿐이라 그것만 세운다. */
    private static BatchRunHistoryJpaEntity row(String batchName, BatchRunStatus status, String error) {
        BatchRunHistoryJpaEntity entity = mock(BatchRunHistoryJpaEntity.class);
        when(entity.getId()).thenReturn(7L);
        when(entity.getBatchName()).thenReturn(batchName);
        when(entity.getRunId()).thenReturn("run-1");
        when(entity.getTargetDate()).thenReturn(LocalDate.of(2026, 9, 1));
        when(entity.getStatus()).thenReturn(status);
        when(entity.getStartedAt()).thenReturn(LocalDateTime.of(2026, 9, 1, 3, 40));
        when(entity.getCompletedAt()).thenReturn(LocalDateTime.of(2026, 9, 1, 3, 41));
        when(entity.getProcessedCount()).thenReturn(120);
        when(entity.getErrorMessage()).thenReturn(error);
        when(entity.getTriggeredBy()).thenReturn("scheduler");
        return entity;
    }

    private void searchReturns(BatchRunHistoryJpaEntity... rows) {
        when(repository.search(any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(rows), PageRequest.of(0, 50), rows.length));
    }

    @Test
    @DisplayName("실행 이력을 실패 사유까지 실어 돌려준다 — 실패 사유가 안 나가면 원장을 볼 이유가 없다")
    void 목록_조회() throws Exception {
        searchReturns(row("point-expiry", BatchRunStatus.FAILED, "커넥션 없음"));

        mockMvc.perform(get("/admin/batch-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].batchName").value("point-expiry"))
                .andExpect(jsonPath("$.content[0].status").value("FAILED"))
                .andExpect(jsonPath("$.content[0].errorMessage").value("커넥션 없음"))
                .andExpect(jsonPath("$.content[0].processedCount").value(120))
                .andExpect(jsonPath("$.content[0].triggeredBy").value("scheduler"));
    }

    @Test
    @DisplayName("필터 세 개가 그대로 저장소까지 내려간다")
    void 필터_전달() throws Exception {
        searchReturns();

        mockMvc.perform(get("/admin/batch-runs")
                        .param("batchName", "expiry-notice")
                        .param("status", "FAILED")
                        .param("targetDate", "2026-09-01"))
                .andExpect(status().isOk());

        verify(repository).search(eq("expiry-notice"), eq(BatchRunStatus.FAILED),
                eq(LocalDate.of(2026, 9, 1)), any());
    }

    @Test
    @DisplayName("size 는 200 을 넘지 못하고 0 이하로도 못 내려간다 — 상한이 없으면 전 이력이 한 응답에 실린다")
    void 페이지_크기_상한() throws Exception {
        searchReturns();
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);

        mockMvc.perform(get("/admin/batch-runs").param("size", "100000")).andExpect(status().isOk());
        mockMvc.perform(get("/admin/batch-runs").param("size", "0").param("page", "-5"))
                .andExpect(status().isOk());

        verify(repository, org.mockito.Mockito.times(2))
                .search(any(), any(), any(), pageable.capture());
        assertThat(pageable.getAllValues().get(0).getPageSize()).isEqualTo(200);
        // 0 은 1 로 올라가고, 음수 페이지는 0 으로 눌린다. 둘 다 그대로 내려가면 예외가 난다.
        assertThat(pageable.getAllValues().get(1).getPageSize()).isEqualTo(1);
        assertThat(pageable.getAllValues().get(1).getPageNumber()).isZero();
    }

    @Test
    @DisplayName("배치별 최근 1건 — 이 화면의 핵심이다")
    void 최근_실행() throws Exception {
        // row() 안에서 다시 when() 을 부르므로 먼저 다 만들어 둔다 — 스터빙 안에 스터빙을 넣으면
        // Mockito 가 UnfinishedStubbingException 을 던진다.
        BatchRunHistoryJpaEntity latest = row("point-expiry", BatchRunStatus.SUCCEEDED, null);
        when(repository.findLatestPerBatch()).thenReturn(List.of(latest));

        mockMvc.perform(get("/admin/batch-runs/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].batchName").value("point-expiry"))
                .andExpect(jsonPath("$[0].status").value("SUCCEEDED"));
    }

    @Test
    @DisplayName("재실행 가능한 배치 목록은 dry-run 지원 여부까지 알려준다")
    void 재실행_가능_목록() throws Exception {
        RerunnableBatch batch = new RerunnableBatch() {
            @Override public String batchName() { return "expiry-notice"; }
            @Override public String description() { return "만료 예고 통보"; }
            @Override public boolean supportsDryRun() { return true; }
            @Override public BatchRunOutcome rerun(LocalDate targetDate, boolean dryRun) {
                throw new UnsupportedOperationException();
            }
        };
        when(rerunService.available()).thenReturn(List.of(batch));

        mockMvc.perform(get("/admin/batch-runs/rerunnable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].batchName").value("expiry-notice"))
                .andExpect(jsonPath("$[0].description").value("만료 예고 통보"))
                .andExpect(jsonPath("$[0].supportsDryRun").value(true));
    }

    @Test
    @DisplayName("dryRun 을 안 적으면 실제 실행이다 — 널이 true 로 새면 재실행 호출이 아무것도 안 한다")
    void dryRun_기본값은_거짓() throws Exception {
        when(rerunService.rerun(anyString(), any(), anyBoolean(), anyString())).thenReturn(31);

        mockMvc.perform(post("/admin/batch-runs/expiry-notice/rerun")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetDate\":\"2026-09-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.processedCount").value(31))
                .andExpect(jsonPath("$.batchName").value("expiry-notice"))
                .andExpect(jsonPath("$.targetDate").value("2026-09-01"));

        verify(rerunService).rerun(eq("expiry-notice"), eq(LocalDate.of(2026, 9, 1)), eq(false), anyString());
    }

    @Test
    @DisplayName("dryRun=true 는 그대로 전달된다")
    void dryRun_명시() throws Exception {
        when(rerunService.rerun(anyString(), any(), anyBoolean(), anyString())).thenReturn(31);

        mockMvc.perform(post("/admin/batch-runs/expiry-notice/rerun")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetDate\":\"2026-09-01\",\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true));

        verify(rerunService).rerun(anyString(), any(), eq(true), anyString());
    }

    @Test
    @DisplayName("누가 돌렸는지 기록된다 — 같은 날짜가 두 번 계산된 이유를 나중에 여기서 읽는다")
    void 실행자_기록() throws Exception {
        when(rerunService.rerun(anyString(), any(), anyBoolean(), anyString())).thenReturn(0);

        mockMvc.perform(post("/admin/batch-runs/point-expiry/rerun")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetDate\":\"2026-09-01\"}"))
                .andExpect(status().isOk());

        ArgumentCaptor<String> actor = ArgumentCaptor.forClass(String.class);
        verify(rerunService).rerun(anyString(), any(), anyBoolean(), actor.capture());
        // 인증 컨텍스트가 없는 이 테스트에서는 "admin". 값이 무엇이든 빈 문자열이면 안 된다 —
        // triggeredBy 가 비면 원장에서 스케줄 실행과 사람 실행을 구분할 수 없다.
        assertThat(actor.getValue()).isNotBlank().startsWith("admin");
    }

    @Test
    @DisplayName("targetDate 없이 재실행을 부르면 실행되지 않는다")
    void targetDate_필수() throws Exception {
        mockMvc.perform(post("/admin/batch-runs/point-expiry/rerun")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(200));

        verify(rerunService, org.mockito.Mockito.never())
                .rerun(anyString(), any(), anyBoolean(), anyString());
    }

    @Test
    @DisplayName("이력이 없으면 빈 페이지 — 예외가 아니다")
    void 빈_이력() throws Exception {
        searchReturns();

        mockMvc.perform(get("/admin/batch-runs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));

        verify(repository, org.mockito.Mockito.never()).findLatestPerBatch();
        verify(rerunService, org.mockito.Mockito.never()).available();
    }
}
