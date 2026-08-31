import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>반려에는 사유가 반드시 있어야 한다.</b> 서버도 빈 사유를 400 으로 막지만, 화면이
 * 먼저 막지 않으면 사유 없는 반려가 계속 시도된다. 사유 없는 반려를 받은 셀러는 무엇을
 * 고쳐야 하는지 알 수 없어 같은 신청서를 그대로 다시 올린다 — 큐가 줄지 않는다.
 *
 * <p>② <b>승인을 '등록 완료' 로 쓰지 않는다.</b> 승인은 카탈로그 등록 <i>요청</i>이고 상품은
 * order-service 가 이벤트를 받아 만든다. 화면이 이 시차를 감추면 등록이 실패한 신청서를
 * 아무도 다시 보지 않는다.
 *
 * <p>③ <b>처리 후에는 큐를 다시 읽는다.</b> 처리한 건을 화면에서만 지우면, 서버에서 실패해
 * 아직 대기 중인 건이 운영자 화면에서 사라진다.
 */

vi.mock('@/api/seller', async () => {
  const actual = await vi.importActual<typeof import('@/api/seller')>('@/api/seller');
  return {
    ...actual,
    sellerApi: {
      profile: vi.fn(), members: vi.fn(), submissions: vi.fn(), submission: vi.fn(),
      createSubmission: vi.fn(), updateSubmission: vi.fn(), submitSubmission: vi.fn(),
      orders: vi.fn(), order: vi.fn(), registerShipment: vi.fn(),
      pendingSubmissions: vi.fn(), approveSubmission: vi.fn(), rejectSubmission: vi.fn(),
    },
  };
});

const { sellerApi } = await import('@/api/seller');
const { default: ProductSubmissionReviewPage } =
  await import('@/pages/system/ProductSubmissionReviewPage');

const mock = vi.mocked(sellerApi);

const submission = (over: Record<string, unknown> = {}) => ({
  submissionId: 41,
  sellerId: 7,
  type: 'NEW' as const,
  baseProductId: null,
  name: '수제 잼',
  description: '딸기 100%',
  price: 12000,
  stock: 30,
  category: '식품',
  imageUrl: null,
  displayVisible: true,
  status: 'SUBMITTED' as const,
  rejectReason: null,
  productId: null,
  awaitingCatalog: false,
  createdByUserId: 1,
  decidedByUserId: null,
  submittedAt: null,
  decidedAt: null,
  ...over,
});

const queue = (over: Record<string, unknown> = {}) => ({
  content: [submission()], page: 0, size: 20, totalElements: 1, totalPages: 1, ...over,
});

const httpError = (status: number, message: string) => ({ response: { status, data: { message } } });

describe('ProductSubmissionReviewPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mock.pendingSubmissions.mockResolvedValue(queue());
  });

  it('처음 불러오는 동안에는 진행 표시를 낸다', () => {
    mock.pendingSubmissions.mockReturnValue(new Promise(() => {}));

    render(<ProductSubmissionReviewPage />);

    expect(screen.getByTestId('review-loading')).toBeInTheDocument();
  });

  it('심사 대기 큐를 첫 쪽부터 읽는다', async () => {
    render(<ProductSubmissionReviewPage />);

    await screen.findByTestId('review-table');
    expect(mock.pendingSubmissions).toHaveBeenCalledWith(0, 20);
  });

  it('신규와 수정 신청을 구분해 적는다', async () => {
    mock.pendingSubmissions.mockResolvedValue(queue({
      content: [
        submission({ submissionId: 41 }),
        submission({ submissionId: 42, type: 'UPDATE', baseProductId: 900 }),
      ],
      totalElements: 2,
    }));

    render(<ProductSubmissionReviewPage />);

    const table = await screen.findByTestId('review-table');
    expect(table).toHaveTextContent('신규');
    expect(table).toHaveTextContent('수정 (상품 900)');
  });

  it('제출일시가 없으면 지어내지 않고 —로 비운다', async () => {
    render(<ProductSubmissionReviewPage />);

    const table = await screen.findByTestId('review-table');
    expect(table).toHaveTextContent('12,000원');
    expect(table).toHaveTextContent('—');
  });

  it('설명이 없는 신청서도 상품명은 그대로 나온다', async () => {
    mock.pendingSubmissions.mockResolvedValue(queue({
      content: [submission({ description: null })],
    }));

    render(<ProductSubmissionReviewPage />);

    const table = await screen.findByTestId('review-table');
    expect(table).toHaveTextContent('수제 잼');
    expect(table).not.toHaveTextContent('딸기 100%');
  });

  /** ②③번 규율 — 이 테스트가 이 화면에서 가장 중요하다. */
  it('승인 안내는 등록 완료가 아니라 잠시 뒤 반영이라고 적고, 큐를 다시 읽는다', async () => {
    mock.approveSubmission.mockResolvedValue(submission({ status: 'APPROVED' }));
    render(<ProductSubmissionReviewPage />);
    await screen.findByTestId('review-table');

    await userEvent.click(screen.getByTestId('approve-41'));

    const notice = await screen.findByTestId('review-notice');
    expect(notice).toHaveTextContent('신청서 41 승인');
    expect(notice).toHaveTextContent('잠시 뒤 반영됩니다');
    expect(notice).not.toHaveTextContent('등록 완료');
    expect(mock.approveSubmission).toHaveBeenCalledWith(41);
    await waitFor(() => expect(mock.pendingSubmissions).toHaveBeenCalledTimes(2));
  });

  it('승인이 실패하면 승인 안내를 남기지 않는다', async () => {
    mock.approveSubmission.mockRejectedValue(httpError(409, '이미 처리된 신청서입니다.'));
    render(<ProductSubmissionReviewPage />);
    await screen.findByTestId('review-table');

    await userEvent.click(screen.getByTestId('approve-41'));

    expect(await screen.findByTestId('review-error')).toHaveTextContent('이미 처리된 신청서입니다.');
    expect(screen.queryByTestId('review-notice')).toBeNull();
  });

  /** ①번 규율. */
  it('사유가 비어 있으면 반려를 확정할 수 없다', async () => {
    render(<ProductSubmissionReviewPage />);
    await screen.findByTestId('review-table');

    await userEvent.click(screen.getByTestId('reject-41'));

    await screen.findByTestId('reject-form');
    expect(screen.getByTestId('reject-confirm')).toBeDisabled();

    // 공백만 적은 것은 적지 않은 것이다.
    await userEvent.type(screen.getByTestId('reject-reason'), '   ');
    expect(screen.getByTestId('reject-confirm')).toBeDisabled();
    expect(mock.rejectSubmission).not.toHaveBeenCalled();
  });

  it('사유를 적어 반려하면 앞뒤 공백을 떼고 보내고 큐를 다시 읽는다', async () => {
    mock.rejectSubmission.mockResolvedValue(submission({ status: 'REJECTED' }));
    render(<ProductSubmissionReviewPage />);
    await screen.findByTestId('review-table');

    await userEvent.click(screen.getByTestId('reject-41'));
    await userEvent.type(await screen.findByTestId('reject-reason'), '  상품 사진이 없습니다  ');
    await userEvent.click(screen.getByTestId('reject-confirm'));

    await waitFor(() =>
      expect(mock.rejectSubmission).toHaveBeenCalledWith(41, '상품 사진이 없습니다'));
    const notice = await screen.findByTestId('review-notice');
    expect(notice).toHaveTextContent('신청서 41 반려');
    expect(notice).toHaveTextContent('셀러가 수정 후 다시 올릴 수 있습니다');
    // 확정된 반려 폼은 닫힌다 — 열린 채로 두면 같은 건을 두 번 반려한다.
    await waitFor(() => expect(screen.queryByTestId('reject-form')).toBeNull());
    await waitFor(() => expect(mock.pendingSubmissions).toHaveBeenCalledTimes(2));
  });

  it('반려가 실패하면 폼을 닫지 않는다', async () => {
    mock.rejectSubmission.mockRejectedValue(httpError(400, '사유가 필요합니다.'));
    render(<ProductSubmissionReviewPage />);
    await screen.findByTestId('review-table');

    await userEvent.click(screen.getByTestId('reject-41'));
    await userEvent.type(await screen.findByTestId('reject-reason'), '사진 없음');
    await userEvent.click(screen.getByTestId('reject-confirm'));

    expect(await screen.findByTestId('review-error')).toHaveTextContent('사유가 필요합니다.');
    expect(screen.getByTestId('reject-form')).toBeInTheDocument();
    expect(screen.queryByTestId('review-notice')).toBeNull();
  });

  it('취소하면 사유를 버리고 폼을 닫는다', async () => {
    render(<ProductSubmissionReviewPage />);
    await screen.findByTestId('review-table');
    await userEvent.click(screen.getByTestId('reject-41'));
    await userEvent.type(await screen.findByTestId('reject-reason'), '사진 없음');

    await userEvent.click(screen.getByTestId('reject-cancel'));

    expect(screen.queryByTestId('reject-form')).toBeNull();
    expect(mock.rejectSubmission).not.toHaveBeenCalled();

    // 다시 열면 앞서 적던 사유가 남아 있지 않다 — 남으면 엉뚱한 건에 그 사유가 붙는다.
    await userEvent.click(screen.getByTestId('reject-41'));
    expect(await screen.findByTestId('reject-reason')).toHaveValue('');
  });

  it('다음 쪽으로 넘어가고, 첫 쪽에서 이전은 잠긴다', async () => {
    mock.pendingSubmissions.mockResolvedValue(queue({ totalElements: 45, totalPages: 3 }));
    render(<ProductSubmissionReviewPage />);
    await screen.findByTestId('review-table');

    expect(screen.getByTestId('review-prev')).toBeDisabled();
    expect(screen.getByTestId('review-total')).toHaveTextContent('대기 45건 · 1/3쪽');

    await userEvent.click(screen.getByTestId('review-next'));

    await waitFor(() => expect(mock.pendingSubmissions).toHaveBeenLastCalledWith(1, 20));
    await waitFor(() => expect(screen.getByTestId('review-total')).toHaveTextContent('2/3쪽'));

    await userEvent.click(screen.getByTestId('review-prev'));
    await waitFor(() => expect(mock.pendingSubmissions).toHaveBeenLastCalledWith(0, 20));
  });

  it('대기 건이 없으면 빈 표가 아니라 없다고 적는다', async () => {
    mock.pendingSubmissions.mockResolvedValue(queue({ content: [], totalElements: 0, totalPages: 0 }));

    render(<ProductSubmissionReviewPage />);

    expect(await screen.findByTestId('review-empty')).toBeInTheDocument();
  });

  it('큐를 못 읽으면 오류를 그린다', async () => {
    mock.pendingSubmissions.mockRejectedValue(httpError(500, ''));

    render(<ProductSubmissionReviewPage />);

    expect(await screen.findByTestId('review-error'))
      .toHaveTextContent('심사 대기 목록을 불러오지 못했습니다');
  });
});
