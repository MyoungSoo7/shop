import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';

/**
 * 이 화면이 지켜야 하는 규율.
 *
 * <p>① <b>저장과 제출은 같은 동작이 아니다.</b> 저장은 작성 중이고 제출은 되돌릴 수 없다.
 * 둘이 한 버튼이면 쓰다 만 신청서가 운영자 큐에 섞이고, 큐를 보는 사람은 그게 미완성인지
 * 알 수 없다.
 *
 * <p>② <b>승인 직후를 '판매중' 으로 그리지 않는다.</b> 승인은 카탈로그 등록 <i>요청</i>이라
 * 상품번호가 아직 없다. 이 상태를 승인과 같은 글자로 그리면, 등록이 실패해 영영 상품이 안
 * 생긴 건과 몇 초 뒤 생길 건이 화면에서 구분되지 않는다 — 아무도 못 보는 실패가 된다.
 *
 * <p>③ <b>403·422 는 사고가 아니라 이 계정의 상태다.</b> 빨간 오류로 그리면 셀러가 아닌
 * 사람이 장애 신고를 하고, 정작 해야 할 일(초대받기)은 화면 어디에도 안 적힌다.
 *
 * <p>④ <b>안 적은 칸은 빈 문자열이 아니라 null 로 보낸다.</b> ''을 그대로 보내면 서버에
 * "빈 값을 적었다" 로 남아, 나중에 미입력 건을 골라낼 방법이 사라진다.
 *
 * <p>⑤ <b>반려 사유는 목록에서 바로 보인다.</b> 눌러 들어가야 보이면 아무도 안 고친다.
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
const { default: SellerProductsPage } = await import('@/pages/seller/SellerProductsPage');

const mock = vi.mocked(sellerApi);

const profile = (over: Record<string, unknown> = {}) => ({
  organizationId: 3,
  organizationName: '레무엘상회',
  orgType: 'SELLER' as const,
  sellerId: 7,
  myRole: 'OWNER' as const,
  canSubmit: true,
  ...over,
});

const submission = (over: Record<string, unknown> = {}) => ({
  submissionId: 41,
  sellerId: 7,
  type: 'NEW' as const,
  baseProductId: null,
  name: '수제 잼',
  description: '딸기',
  price: 12000,
  stock: 30,
  category: '식품',
  imageUrl: null,
  displayVisible: true,
  status: 'DRAFT' as const,
  rejectReason: null,
  productId: null,
  awaitingCatalog: false,
  createdByUserId: 1,
  decidedByUserId: null,
  submittedAt: null,
  decidedAt: null,
  ...over,
});

const listing = (over: Record<string, unknown> = {}) => ({
  content: [submission()], page: 0, size: 20, totalElements: 1, totalPages: 1, ...over,
});

/** 상태코드가 있는 오류 — apiErrorStatus 는 인스턴스가 아니라 모양으로 판별한다. */
const httpError = (status: number, message: string) => ({ response: { status, data: { message } } });

const draw = () => render(<MemoryRouter><SellerProductsPage /></MemoryRouter>);

describe('SellerProductsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    mock.profile.mockResolvedValue(profile());
    mock.members.mockResolvedValue([
      { membershipId: 1, userId: 9, role: 'OWNER', joinedAt: '2026-08-01T09:00:00' },
      { membershipId: 2, userId: 10, role: 'STAFF', joinedAt: '2026-08-02T09:00:00' },
    ]);
    mock.submissions.mockResolvedValue(listing());
  });

  it('처음 불러오는 동안에는 진행 표시만 낸다', () => {
    mock.profile.mockReturnValue(new Promise(() => {}));

    draw();

    expect(screen.getByTestId('seller-loading')).toBeInTheDocument();
  });

  it('조직 이름과 내 역할·구성원 수를 머리에 적는다', async () => {
    draw();

    expect(await screen.findByTestId('seller-org')).toHaveTextContent('레무엘상회');
    expect(screen.getByTestId('seller-role')).toHaveTextContent('대표 · 구성원 2명');
  });

  /** 조직은 있는데 셀러 번호가 아직 없는 상태가 실제로 있다 — 없는 걸 감추지 않는다. */
  it('셀러 번호가 아직 없으면 확인 중이라고 적는다', async () => {
    mock.profile.mockResolvedValue(profile({ sellerId: null }));

    draw();

    expect(await screen.findByTestId('seller-role')).toHaveTextContent('셀러 번호 확인 중');
  });

  /** ③번 규율. */
  it('403 은 오류가 아니라 안내 화면으로 그린다', async () => {
    mock.profile.mockRejectedValue(httpError(403, '셀러 조직의 구성원이 아닙니다.'));

    draw();

    const blocked = await screen.findByTestId('seller-blocked');
    expect(blocked).toHaveTextContent('셀러 조직의 구성원이 아닙니다.');
    expect(screen.queryByTestId('seller-error')).toBeNull();
    expect(screen.queryByTestId('submission-form')).toBeNull();
  });

  it('422(파는 조직이 아님)도 같은 안내 화면이다', async () => {
    mock.profile.mockRejectedValue(httpError(422, '판매 조직이 아닙니다.'));

    draw();

    expect(await screen.findByTestId('seller-blocked')).toHaveTextContent('판매 조직이 아닙니다.');
  });

  /** 반대쪽 — 500 은 진짜 사고다. 안내로 삼키면 장애가 안 보인다. */
  it('500 은 안내가 아니라 오류로 그린다', async () => {
    mock.profile.mockRejectedValue(httpError(500, ''));

    draw();

    expect(await screen.findByTestId('seller-error'))
      .toHaveTextContent('셀러 정보를 불러오지 못했습니다');
    expect(screen.queryByTestId('seller-blocked')).toBeNull();
  });

  /** ④번 규율. */
  it('안 적은 칸은 빈 문자열이 아니라 null 로 보낸다', async () => {
    mock.createSubmission.mockResolvedValue(submission({ submissionId: 55 }));
    draw();
    await screen.findByTestId('submission-form');

    await userEvent.type(screen.getByTestId('form-name'), '수제 잼');
    await userEvent.click(screen.getByTestId('form-save'));

    await waitFor(() => expect(mock.createSubmission).toHaveBeenCalledWith({
      name: '수제 잼', description: null, price: 0, stock: 0,
      category: null, imageUrl: null, displayVisible: true,
    }));
  });

  /** ①번 규율 — 저장은 제출이 아니라는 걸 문구가 말해야 한다. */
  it('저장하면 아직 심사에 올라가지 않았다고 알린다', async () => {
    mock.createSubmission.mockResolvedValue(submission({ submissionId: 55 }));
    draw();
    await screen.findByTestId('submission-form');

    await userEvent.click(screen.getByTestId('form-save'));

    const notice = await screen.findByTestId('seller-notice');
    expect(notice).toHaveTextContent('신청서 55 를 저장했습니다');
    expect(notice).toHaveTextContent('아직 심사에 올라가지 않았습니다');
    expect(mock.submitSubmission).not.toHaveBeenCalled();
  });

  it('저장에 실패하면 성공 안내를 남기지 않는다', async () => {
    mock.createSubmission.mockRejectedValue(httpError(400, '상품명은 필수입니다.'));
    draw();
    await screen.findByTestId('submission-form');

    await userEvent.click(screen.getByTestId('form-save'));

    expect(await screen.findByTestId('seller-error')).toHaveTextContent('상품명은 필수입니다.');
    expect(screen.queryByTestId('seller-notice')).toBeNull();
  });

  it('목록의 번호를 누르면 그 신청서를 폼으로 불러 수정한다', async () => {
    mock.submission.mockResolvedValue(submission({ description: null, category: null }));
    mock.updateSubmission.mockResolvedValue(submission());
    draw();
    await screen.findByTestId('submissions-table');

    await userEvent.click(screen.getByTestId('submission-41'));

    expect(await screen.findByText('신청서 41 수정')).toBeInTheDocument();
    expect(screen.getByTestId('form-name')).toHaveValue('수제 잼');

    await userEvent.click(screen.getByTestId('form-save'));

    await waitFor(() => expect(mock.updateSubmission).toHaveBeenCalledWith(41, expect.objectContaining({
      name: '수제 잼', price: 12000, stock: 30,
    })));
    expect(mock.createSubmission).not.toHaveBeenCalled();
  });

  it('신청서를 못 불러오면 오류만 내고 폼은 새 등록으로 남는다', async () => {
    mock.submission.mockRejectedValue(httpError(404, '신청서를 찾을 수 없습니다.'));
    draw();
    await screen.findByTestId('submissions-table');

    await userEvent.click(screen.getByTestId('submission-41'));

    expect(await screen.findByTestId('seller-error')).toHaveTextContent('신청서를 찾을 수 없습니다.');
    expect(screen.getByText('새 상품 등록')).toBeInTheDocument();
  });

  /** ①번 규율 — 제출은 별도 버튼이고, 고칠 수 있는 상태에서만 뜬다. */
  it('심사 요청은 작성 중인 신청서를 연 뒤에만 누를 수 있다', async () => {
    mock.submission.mockResolvedValue(submission());
    mock.submitSubmission.mockResolvedValue(submission({ status: 'SUBMITTED' }));
    draw();
    await screen.findByTestId('submissions-table');

    // 새 등록 상태에서는 아예 없다 — 저장되지 않은 것을 심사에 올릴 수는 없다.
    expect(screen.queryByTestId('form-submit')).toBeNull();

    await userEvent.click(screen.getByTestId('submission-41'));
    await userEvent.click(await screen.findByTestId('form-submit'));

    await waitFor(() => expect(mock.submitSubmission).toHaveBeenCalledWith(41));
    expect(await screen.findByTestId('seller-notice')).toHaveTextContent('심사에 올렸습니다');
  });

  it('이미 심사 중인 신청서에는 심사 요청 버튼을 그리지 않는다', async () => {
    mock.submission.mockResolvedValue(submission({ status: 'SUBMITTED' }));
    draw();
    await screen.findByTestId('submissions-table');

    await userEvent.click(screen.getByTestId('submission-41'));

    expect(await screen.findByText('신청서 41 수정')).toBeInTheDocument();
    expect(screen.queryByTestId('form-submit')).toBeNull();
    expect(screen.queryByTestId('form-submit-blocked')).toBeNull();
  });

  it('반려된 신청서는 다시 고쳐 올릴 수 있다', async () => {
    mock.submission.mockResolvedValue(submission({ status: 'REJECTED', rejectReason: '사진 없음' }));
    draw();
    await screen.findByTestId('submissions-table');

    await userEvent.click(screen.getByTestId('submission-41'));

    expect(await screen.findByTestId('form-submit')).toBeInTheDocument();
  });

  /** STAFF 는 버튼 대신 이유가 보인다 — 버튼만 감추면 왜 안 되는지 아무도 모른다. */
  it('STAFF 에게는 심사 요청 버튼 대신 이유를 적는다', async () => {
    mock.profile.mockResolvedValue(profile({ myRole: 'STAFF', canSubmit: false }));
    mock.submission.mockResolvedValue(submission());
    draw();
    await screen.findByTestId('submissions-table');

    await userEvent.click(screen.getByTestId('submission-41'));

    expect(await screen.findByTestId('form-submit-blocked'))
      .toHaveTextContent('대표 · 관리자만');
    expect(screen.queryByTestId('form-submit')).toBeNull();
  });

  it('심사 요청이 실패하면 올라간 것처럼 적지 않는다', async () => {
    mock.submission.mockResolvedValue(submission());
    mock.submitSubmission.mockRejectedValue(httpError(422, '판매가는 0보다 커야 합니다.'));
    draw();
    await screen.findByTestId('submissions-table');

    await userEvent.click(screen.getByTestId('submission-41'));
    await userEvent.click(await screen.findByTestId('form-submit'));

    expect(await screen.findByTestId('seller-error')).toHaveTextContent('판매가는 0보다 커야 합니다.');
    expect(screen.queryByTestId('seller-notice')).toBeNull();
  });

  it('새 신청서 버튼은 폼을 비우고 안내를 지운다', async () => {
    mock.submission.mockResolvedValue(submission());
    draw();
    await screen.findByTestId('submissions-table');
    await userEvent.click(screen.getByTestId('submission-41'));
    await screen.findByText('신청서 41 수정');

    await userEvent.click(screen.getByTestId('start-new'));

    expect(screen.getByText('새 상품 등록')).toBeInTheDocument();
    expect(screen.getByTestId('form-name')).toHaveValue('');
  });

  /** ②번 규율 — 이 테스트가 이 화면에서 가장 중요하다. */
  it('승인됐지만 상품번호가 없는 건은 판매중이 아니라 등록 처리 중이다', async () => {
    mock.submissions.mockResolvedValue(listing({
      content: [
        submission({ submissionId: 41, status: 'APPROVED', awaitingCatalog: true, productId: null }),
        submission({ submissionId: 42, status: 'APPROVED', awaitingCatalog: false, productId: 900 }),
      ],
      totalElements: 2,
    }));

    draw();

    expect(await screen.findByTestId('submission-status-41')).toHaveTextContent('등록 처리 중');
    expect(screen.getByTestId('submission-status-42')).toHaveTextContent('판매중');
  });

  /** ⑤번 규율. */
  it('반려 사유는 목록에서 바로 보이고, 사유가 없으면 없다고 적는다', async () => {
    mock.submissions.mockResolvedValue(listing({
      content: [
        submission({ submissionId: 41, status: 'REJECTED', rejectReason: '사진이 없습니다' }),
        submission({ submissionId: 42, status: 'REJECTED', rejectReason: null }),
      ],
      totalElements: 2,
    }));

    draw();

    const table = await screen.findByTestId('submissions-table');
    expect(table).toHaveTextContent('사진이 없습니다');
    expect(table).toHaveTextContent('사유 없음');
  });

  it('수정 신청은 어느 상품을 고치는 건지 적는다', async () => {
    mock.submissions.mockResolvedValue(listing({
      content: [submission({ type: 'UPDATE', baseProductId: 900, status: 'SUBMITTED' })],
    }));

    draw();

    expect(await screen.findByTestId('submissions-table')).toHaveTextContent('상품 900 수정');
  });

  it('상태 필터는 그 상태만 서버에 싣고 첫 쪽부터 다시 읽는다', async () => {
    draw();
    await screen.findByTestId('submissions-table');

    await userEvent.click(screen.getByTestId('filter-REJECTED'));

    await waitFor(() => expect(mock.submissions).toHaveBeenLastCalledWith('REJECTED', 0, 20));
  });

  it('다음 쪽은 같은 상태 필터를 유지하고, 첫 쪽에서 이전은 잠긴다', async () => {
    mock.submissions.mockResolvedValue(listing({ totalElements: 45, totalPages: 3 }));
    draw();
    await screen.findByTestId('submissions-table');

    expect(screen.getByTestId('submissions-prev')).toBeDisabled();
    await userEvent.click(screen.getByTestId('submissions-next'));

    await waitFor(() => expect(mock.submissions).toHaveBeenLastCalledWith(null, 1, 20));
  });

  it('목록 재조회가 실패하면 오류를 그린다', async () => {
    draw();
    await screen.findByTestId('submissions-table');
    mock.submissions.mockRejectedValue(httpError(500, ''));

    await userEvent.click(screen.getByTestId('filter-DRAFT'));

    expect(await screen.findByTestId('seller-error'))
      .toHaveTextContent('신청서 목록을 불러오지 못했습니다');
  });

  it('신청서가 없으면 빈 표가 아니라 없다고 적는다', async () => {
    mock.submissions.mockResolvedValue(listing({ content: [], totalElements: 0, totalPages: 0 }));

    draw();

    expect(await screen.findByTestId('submissions-empty')).toBeInTheDocument();
  });
});
