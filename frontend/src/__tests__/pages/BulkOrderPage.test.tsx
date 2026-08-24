import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import BulkOrderPage from '@/pages/BulkOrderPage';
import {
  bulkOrderApi,
  type BulkOrderColumn,
  type BulkOrderDraft,
  type BulkOrderRow,
} from '@/api/bulkOrder';

vi.mock('@/api/bulkOrder', () => ({
  bulkOrderApi: {
    columns: vi.fn(),
    list: vi.fn(),
    get: vi.fn(),
    upload: vi.fn(),
    revalidate: vi.fn(),
    confirm: vi.fn(),
    discard: vi.fn(),
  },
}));

const mocked = vi.mocked(bulkOrderApi);

const columns: BulkOrderColumn[] = [
  {
    columnIndex: 0, itemCode: 'PRODUCT_ID', name: '상품ID', required: true,
    maxLength: null, validationType: 'NUMBER', validationText: null,
  },
  {
    columnIndex: 1, itemCode: 'QTY', name: '수량', required: true,
    maxLength: 5, validationType: 'NONE', validationText: null,
  },
  {
    columnIndex: 2, itemCode: 'MEMO', name: '메모', required: false,
    maxLength: 100, validationType: 'REGEX', validationText: '^[가-힣 ]*$',
  },
];

const row = (overrides: Partial<BulkOrderRow> = {}): BulkOrderRow => ({
  rowNumber: 1,
  valid: true,
  errorMessage: null,
  createdOrderId: null,
  cells: [
    { columnIndex: 0, value: '10', valid: true, errorMessage: null },
    { columnIndex: 1, value: '2', valid: true, errorMessage: null },
    { columnIndex: 2, value: null, valid: true, errorMessage: null },
  ],
  ...overrides,
});

const draft = (overrides: Partial<BulkOrderDraft> = {}): BulkOrderDraft => ({
  id: 1,
  fileName: 'orders.csv',
  status: 'VALIDATED',
  rowCount: 1,
  validRowCount: 1,
  uploadedAt: '2026-08-20T10:00:00',
  updatedAt: '2026-08-20T10:00:00',
  rows: [row()],
  ...overrides,
});

const csv = () => new File(['상품ID,수량\n10,2'], 'orders.csv', { type: 'text/csv' });

describe('BulkOrderPage — 올리는 것과 주문이 나가는 것은 다른 버튼이다', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.columns.mockResolvedValue(columns);
    mocked.list.mockResolvedValue([]);
  });

  it('업로드는 검증까지만 한다 — 올린 것만으로 주문이 나가지 않는다', async () => {
    mocked.upload.mockResolvedValue(draft({ status: 'UPLOADED' }));
    const user = userEvent.setup();
    render(<BulkOrderPage />);

    await user.upload(await screen.findByLabelText(/CSV 업로드/), csv());

    await waitFor(() => expect(mocked.upload).toHaveBeenCalled());
    expect(mocked.confirm).not.toHaveBeenCalled();
  });

  it('검증을 통과하지 않은 초안은 "실주문 전환"을 누를 수 없다', async () => {
    mocked.upload.mockResolvedValue(draft({ status: 'REJECTED', validRowCount: 0 }));
    const user = userEvent.setup();
    render(<BulkOrderPage />);

    await user.upload(await screen.findByLabelText(/CSV 업로드/), csv());

    expect(await screen.findByRole('button', { name: '실주문 전환' })).toBeDisabled();
  });

  it('검증을 통과하면 전환 버튼이 열린다', async () => {
    mocked.upload.mockResolvedValue(draft({ status: 'VALIDATED' }));
    const user = userEvent.setup();
    render(<BulkOrderPage />);

    await user.upload(await screen.findByLabelText(/CSV 업로드/), csv());

    expect(await screen.findByRole('button', { name: '실주문 전환' })).toBeEnabled();
  });

  it('전환이 끝난 초안은 재검증·폐기가 모두 잠긴다 — 나간 주문을 되돌리는 문이 아니다', async () => {
    mocked.list.mockResolvedValue([draft({ status: 'CONFIRMED' })]);
    mocked.get.mockResolvedValue(draft({ status: 'CONFIRMED' }));
    const user = userEvent.setup();
    render(<BulkOrderPage />);

    await user.click(await screen.findByRole('button', { name: 'orders.csv' }));

    expect(await screen.findByRole('button', { name: '재검증' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '폐기' })).toBeDisabled();
    expect(screen.getByRole('button', { name: '실주문 전환' })).toBeDisabled();
  });
});

describe('BulkOrderPage — 오류는 셀 단위로 짚는다', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.columns.mockResolvedValue(columns);
    mocked.list.mockResolvedValue([]);
  });

  it('틀린 셀에 사유가 붙는다 — "이 행 어딘가"만 알려 주면 결국 눈으로 훑게 된다', async () => {
    mocked.upload.mockResolvedValue(draft({
      status: 'REJECTED',
      validRowCount: 0,
      rows: [row({
        valid: false,
        errorMessage: '수량은 1 이상이어야 합니다',
        cells: [
          { columnIndex: 0, value: '10', valid: true, errorMessage: null },
          { columnIndex: 1, value: '0', valid: false, errorMessage: '수량은 1 이상이어야 합니다' },
          { columnIndex: 2, value: null, valid: true, errorMessage: null },
        ],
      })],
    }));
    const user = userEvent.setup();
    render(<BulkOrderPage />);

    await user.upload(await screen.findByLabelText(/CSV 업로드/), csv());

    const badCell = await screen.findByTitle('수량은 1 이상이어야 합니다');
    expect(badCell).toHaveTextContent('0');
    expect(screen.getByText('수량은 1 이상이어야 합니다')).toBeInTheDocument();
  });

  it('빈 셀은 "-" 로 자리를 지킨다 — 칸이 밀리면 어느 열이 틀렸는지 못 읽는다', async () => {
    mocked.upload.mockResolvedValue(draft());
    const user = userEvent.setup();
    render(<BulkOrderPage />);

    await user.upload(await screen.findByLabelText(/CSV 업로드/), csv());

    const detail = (await screen.findAllByRole('table'))[1];
    expect(within(detail).getByText('-')).toBeInTheDocument();
  });

  it('전환된 행에는 만들어진 주문번호가 붙는다', async () => {
    mocked.upload.mockResolvedValue(draft({
      status: 'CONFIRMED',
      rows: [row({ createdOrderId: 5001 })],
    }));
    const user = userEvent.setup();
    render(<BulkOrderPage />);

    await user.upload(await screen.findByLabelText(/CSV 업로드/), csv());

    expect(await screen.findByText('주문 #5001')).toBeInTheDocument();
  });
});

describe('BulkOrderPage — 양식은 서버가 정한다', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.columns.mockResolvedValue(columns);
    mocked.list.mockResolvedValue([]);
  });

  it('열 정의를 그대로 그린다 — 화면에 하드코딩하면 DB 가 바뀌어도 안내가 안 바뀐다', async () => {
    render(<BulkOrderPage />);

    const form = (await screen.findAllByRole('table'))[0];
    expect(within(form).getByText('상품ID')).toBeInTheDocument();
    expect(within(form).getByText('REGEX (^[가-힣 ]*$)')).toBeInTheDocument();
    expect(await screen.findByText('상품ID,수량,메모')).toBeInTheDocument();
  });

  it('필수·선택과 최대 길이를 열마다 밝힌다', async () => {
    render(<BulkOrderPage />);

    // 헤더에도 "필수"가 있으므로 본문으로 좁혀 센다.
    const body = (await screen.findAllByRole('rowgroup'))[1];
    expect(within(body).getAllByText('필수')).toHaveLength(2);
    expect(within(body).getByText('선택')).toBeInTheDocument();
    expect(within(body).getByText('100')).toBeInTheDocument();
  });

  it('올린 파일이 없으면 그렇게 말한다', async () => {
    render(<BulkOrderPage />);

    expect(await screen.findByText('업로드한 파일이 없습니다.')).toBeInTheDocument();
  });

  it('파일명이 없는 초안은 번호로 부른다', async () => {
    mocked.list.mockResolvedValue([draft({ fileName: null, status: 'UPLOADED' })]);
    render(<BulkOrderPage />);

    expect(await screen.findByRole('button', { name: '초안 #1' })).toBeInTheDocument();
    expect(screen.getByText('검증 대기')).toBeInTheDocument();
  });
});

describe('BulkOrderPage — 조작과 실패', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.columns.mockResolvedValue(columns);
    mocked.list.mockResolvedValue([draft({ status: 'VALIDATED' })]);
    mocked.get.mockResolvedValue(draft({ status: 'VALIDATED' }));
  });

  it('재검증은 서버를 다시 불러 목록까지 새로 읽는다', async () => {
    mocked.revalidate.mockResolvedValue(draft({ status: 'REJECTED', validRowCount: 0 }));
    const user = userEvent.setup();
    render(<BulkOrderPage />);
    await user.click(await screen.findByRole('button', { name: 'orders.csv' }));

    const before = mocked.list.mock.calls.length;
    await user.click(await screen.findByRole('button', { name: '재검증' }));

    await waitFor(() => expect(mocked.revalidate).toHaveBeenCalledWith(1));
    await waitFor(() => expect(mocked.list.mock.calls.length).toBeGreaterThan(before));
  });

  it('전환 결과는 생성·실패 건수를 나눠 말한다', async () => {
    mocked.confirm.mockResolvedValue({
      draftId: 1, status: 'CONFIRMED', created: 8, failed: 0, lines: [],
    });
    // 열 때는 VALIDATED(전환 가능), 전환 뒤 다시 읽으면 CONFIRMED 다.
    mocked.get
      .mockResolvedValueOnce(draft({ status: 'VALIDATED' }))
      .mockResolvedValueOnce(draft({ status: 'CONFIRMED' }));
    const user = userEvent.setup();
    render(<BulkOrderPage />);
    await user.click(await screen.findByRole('button', { name: 'orders.csv' }));

    await user.click(await screen.findByRole('button', { name: '실주문 전환' }));

    // 건수는 JSX 식으로 끊겨 여러 텍스트 노드에 흩어진다 — 이어 붙인 문장으로 본다.
    await waitFor(() =>
      expect(document.body.textContent).toContain('생성 8건 · 실패 0건'));
    expect(screen.queryByText(/이미 나간 주문은 다시 만들지 않습니다/)).not.toBeInTheDocument();
  });

  it('일부가 실패하면 "이미 나간 주문은 다시 만들지 않는다"고 못박는다', async () => {
    mocked.confirm.mockResolvedValue({
      draftId: 1, status: 'REJECTED', created: 6, failed: 2,
      lines: [{ rowNumber: 3, orderId: null, error: '재고 부족' }],
    });
    const user = userEvent.setup();
    render(<BulkOrderPage />);
    await user.click(await screen.findByRole('button', { name: 'orders.csv' }));

    await user.click(await screen.findByRole('button', { name: '실주문 전환' }));

    expect(await screen.findByText(/이미 나간 주문은 다시 만들지 않습니다/)).toBeInTheDocument();
  });

  it('폐기하면 상세를 닫고 목록을 다시 읽는다', async () => {
    mocked.discard.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<BulkOrderPage />);
    await user.click(await screen.findByRole('button', { name: 'orders.csv' }));
    await screen.findByRole('button', { name: '폐기' });

    await user.click(screen.getByRole('button', { name: '폐기' }));

    await waitFor(() => expect(mocked.discard).toHaveBeenCalledWith(1));
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: '폐기' })).not.toBeInTheDocument());
  });

  it('조작 실패는 서버가 준 사유를 그대로 보여 준다', async () => {
    mocked.confirm.mockRejectedValue({ response: { status: 409, data: { message: '이미 전환된 초안입니다' } } });
    const user = userEvent.setup();
    render(<BulkOrderPage />);
    await user.click(await screen.findByRole('button', { name: 'orders.csv' }));

    await user.click(await screen.findByRole('button', { name: '실주문 전환' }));

    expect(await screen.findByText('이미 전환된 초안입니다')).toBeInTheDocument();
  });

  it('첫 로딩이 실패하면 빈 화면 대신 사유를 남긴다', async () => {
    mocked.columns.mockRejectedValue(new Error('boom'));
    render(<BulkOrderPage />);

    expect(await screen.findByText('대량주문 정보를 불러오지 못했습니다.')).toBeInTheDocument();
  });
});
