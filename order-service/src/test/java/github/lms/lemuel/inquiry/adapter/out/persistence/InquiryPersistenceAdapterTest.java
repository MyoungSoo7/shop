package github.lms.lemuel.inquiry.adapter.out.persistence;

import github.lms.lemuel.inquiry.domain.Inquiry;
import github.lms.lemuel.inquiry.domain.InquiryAnswer;
import github.lms.lemuel.inquiry.domain.InquiryStatus;
import github.lms.lemuel.inquiry.domain.InquiryType;
import github.lms.lemuel.inquiry.domain.exception.InquiryNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 문의 영속 어댑터 회귀 테스트 (Mockito, 실 DB 미접속). */
@ExtendWith(MockitoExtension.class)
@DisplayName("문의 영속 어댑터")
class InquiryPersistenceAdapterTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 27, 10, 0);

    @Mock SpringDataInquiryRepository inquiryRepository;
    @Mock SpringDataInquiryAnswerRepository answerRepository;
    @InjectMocks InquiryPersistenceAdapter adapter;

    private static InquiryJpaEntity entity(Long id, Long userId) {
        return new InquiryJpaEntity(id, userId, InquiryType.PRODUCT, 100L, null,
                "사이즈 문의", "정사이즈인가요?", false, NOW);
    }

    private static InquiryAnswerJpaEntity answerEntity(Long id, Long inquiryId) {
        return new InquiryAnswerJpaEntity(id, inquiryId, 900L, "정사이즈입니다.", NOW.plusHours(1));
    }

    @Test
    @DisplayName("상세는 답변까지 채워 도메인으로 매핑한다")
    void findByIdHydratesAnswers() {
        when(inquiryRepository.findById(1L)).thenReturn(Optional.of(entity(1L, 7L)));
        when(answerRepository.findByInquiryIdOrderByAnsweredAtAsc(1L))
                .thenReturn(List.of(answerEntity(10L, 1L)));

        Inquiry inquiry = adapter.findById(1L).orElseThrow();

        assertThat(inquiry.id()).isEqualTo(1L);
        assertThat(inquiry.type()).isEqualTo(InquiryType.PRODUCT);
        assertThat(inquiry.answers()).extracting(InquiryAnswer::id).containsExactly(10L);
        assertThat(inquiry.status()).isEqualTo(InquiryStatus.ANSWERED);
    }

    @Test
    @DisplayName("없는 문의는 빈 Optional 이다 — 없음의 판정은 서비스가 한다")
    void findByIdEmpty() {
        when(inquiryRepository.findById(1L)).thenReturn(Optional.empty());

        assertThat(adapter.findById(1L)).isEmpty();
        verify(answerRepository, never()).findByInquiryIdOrderByAnsweredAtAsc(anyLong());
    }

    @Test
    @DisplayName("목록의 답변은 부모 id 를 모아 한 번에 읽는다 — 건마다 부르면 목록 한 장이 곧 N+1 이다")
    void listLoadsAnswersInOneBatch() {
        when(inquiryRepository.findByUserIdOrderByAskedAtDesc(anyLong(), any(Pageable.class)))
                .thenReturn(List.of(entity(1L, 7L), entity(2L, 7L), entity(3L, 7L)));
        when(answerRepository.findByInquiryIdInOrderByAnsweredAtAsc(anyCollection()))
                .thenReturn(List.of(answerEntity(10L, 1L), answerEntity(11L, 3L)));

        List<Inquiry> inquiries = adapter.findByUserId(7L, null, 200);

        assertThat(inquiries).hasSize(3);
        assertThat(inquiries.get(0).answers()).hasSize(1);
        assertThat(inquiries.get(1).answers()).isEmpty();
        assertThat(inquiries.get(2).answers()).hasSize(1);
        verify(answerRepository).findByInquiryIdInOrderByAnsweredAtAsc(anyCollection());
        verify(answerRepository, never()).findByInquiryIdOrderByAnsweredAtAsc(anyLong());
    }

    @Test
    @DisplayName("빈 목록이면 답변 조회를 아예 하지 않는다")
    void emptyListSkipsAnswerQuery() {
        when(inquiryRepository.findByProductIdOrderByAskedAtDesc(anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(adapter.findByProductId(100L, 100)).isEmpty();
        verify(answerRepository, never()).findByInquiryIdInOrderByAnsweredAtAsc(anyCollection());
    }

    @Test
    @DisplayName("종류를 주면 종류로 좁힌 질의를 쓴다")
    void filtersByType() {
        when(inquiryRepository.findByUserIdAndTypeOrderByAskedAtDesc(anyLong(), any(), any(Pageable.class)))
                .thenReturn(List.of());

        adapter.findByUserId(7L, InquiryType.ORDER, 200);

        verify(inquiryRepository).findByUserIdAndTypeOrderByAskedAtDesc(anyLong(), any(), any(Pageable.class));
        verify(inquiryRepository, never()).findByUserIdOrderByAskedAtDesc(anyLong(), any(Pageable.class));
    }

    @Test
    @DisplayName("한도는 Pageable 로 넘어간다")
    void limitBecomesPageSize() {
        when(inquiryRepository.findByProductIdOrderByAskedAtDesc(anyLong(), any(Pageable.class)))
                .thenReturn(List.of());

        adapter.findByProductId(100L, 37);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(inquiryRepository).findByProductIdOrderByAskedAtDesc(anyLong(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(37);
    }

    @Test
    @DisplayName("새 문의는 id 없이 넘겨 DB 가 번호를 정하게 한다 — 레거시의 MAX(ID)+1 채번을 없앤 자리다")
    void saveLeavesIdToDatabase() {
        when(inquiryRepository.save(any())).thenReturn(entity(1L, 7L));

        Inquiry saved = adapter.save(Inquiry.ask(7L, InquiryType.PRODUCT, 100L, null,
                "사이즈 문의", "정사이즈인가요?", false, NOW));

        ArgumentCaptor<InquiryJpaEntity> captor = ArgumentCaptor.forClass(InquiryJpaEntity.class);
        verify(inquiryRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isNull();
        assertThat(saved.id()).isEqualTo(1L);
        assertThat(saved.answers()).isEmpty();
    }

    @Test
    @DisplayName("수정은 제목·본문·공개 여부만 건드린다")
    void updateTouchesEditableFieldsOnly() {
        InquiryJpaEntity existing = entity(1L, 7L);
        when(inquiryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(inquiryRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(answerRepository.findByInquiryIdOrderByAnsweredAtAsc(1L)).thenReturn(List.of());

        Inquiry updated = adapter.update(new Inquiry(1L, 7L, InquiryType.PRODUCT, 100L, null,
                "새 제목", "새 본문", true, NOW, List.of()));

        assertThat(updated.subject()).isEqualTo("새 제목");
        assertThat(updated.secret()).isTrue();
        assertThat(existing.getUserId()).isEqualTo(7L);
        assertThat(existing.getAskedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("없는 문의를 수정하면 404")
    void updateMissing() {
        when(inquiryRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.update(new Inquiry(1L, 7L, InquiryType.GENERAL, null, null,
                "제목", "본문", false, NOW, List.of())))
                .isInstanceOf(InquiryNotFoundException.class);
    }

    @Test
    @DisplayName("삭제는 답변부터 — DB 의 CASCADE 는 같은 트랜잭션의 영속성 컨텍스트엔 반영되지 않는다")
    void deleteRemovesAnswersFirst() {
        adapter.delete(1L);

        var order = inOrder(answerRepository, inquiryRepository);
        order.verify(answerRepository).deleteByInquiryId(1L);
        order.verify(inquiryRepository).deleteById(1L);
    }

    @Test
    @DisplayName("답변 삭제는 부모까지 대조한다 — 다른 문의의 답변 번호로는 아무것도 지워지지 않는다")
    void deleteAnswerMatchesParent() {
        when(answerRepository.deleteByIdAndInquiryId(777L, 1L)).thenReturn(0L);
        when(answerRepository.deleteByIdAndInquiryId(10L, 1L)).thenReturn(1L);

        assertThat(adapter.deleteAnswer(1L, 777L)).isFalse();
        assertThat(adapter.deleteAnswer(1L, 10L)).isTrue();
    }

    @Test
    @DisplayName("답변 저장은 부모 id 를 함께 넣는다")
    void addAnswerCarriesParentId() {
        when(answerRepository.save(any())).thenReturn(answerEntity(10L, 1L));

        InquiryAnswer saved = adapter.addAnswer(1L, InquiryAnswer.of(900L, "정사이즈입니다.", NOW.plusHours(1)));

        ArgumentCaptor<InquiryAnswerJpaEntity> captor =
                ArgumentCaptor.forClass(InquiryAnswerJpaEntity.class);
        verify(answerRepository).save(captor.capture());
        assertThat(captor.getValue().getInquiryId()).isEqualTo(1L);
        assertThat(captor.getValue().getId()).isNull();
        assertThat(saved.id()).isEqualTo(10L);
    }

    @Test
    @DisplayName("답변 대기 목록도 같은 경로로 채운다 — 조회와 판정이 어긋날 자리를 남기지 않는다")
    void waitingUsesSameHydration() {
        when(inquiryRepository.findWaiting(any(Pageable.class))).thenReturn(List.of(entity(1L, 7L)));
        when(answerRepository.findByInquiryIdInOrderByAnsweredAtAsc(anyCollection())).thenReturn(List.of());

        List<Inquiry> waiting = adapter.findWaiting(200);

        assertThat(waiting).hasSize(1);
        assertThat(waiting.get(0).status()).isEqualTo(InquiryStatus.WAITING);
    }
}
