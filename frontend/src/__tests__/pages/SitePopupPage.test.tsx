import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import SitePopupPage from '@/pages/system/SitePopupPage';
import { popupApi, type Popup } from '@/api/sitePopup';

vi.mock('@/api/sitePopup', () => ({
  popupApi: {
    list: vi.fn(),
    visible: vi.fn(),
    get: vi.fn(),
    register: vi.fn(),
    update: vi.fn(),
    changeActivation: vi.fn(),
    remove: vi.fn(),
  },
}));

const mocked = vi.mocked(popupApi);

/** 노출 중인 팝업 한 건이 기본값이다 — 상태 조합은 각 테스트가 필요한 것만 뒤집는다. */
const popup = (overrides: Partial<Popup> = {}): Popup => ({
  id: 'p1',
  title: '추석 휴진 안내',
  imageUrl: 'https://cdn/x.png',
  linkUrl: 'https://site/notice/1',
  openInNewWindow: true,
  startsAt: '2026-09-01T12:00:00Z',
  endsAt: '2026-09-30T12:00:00Z',
  sortOrder: 1,
  active: true,
  deleted: false,
  deletedAt: null,
  visible: true,
  scheduled: false,
  expired: false,
  updatedBy: 'admin@lemuel.io',
  version: 0,
  ...overrides,
});

/** 화면이 쓰는 것과 같은 변환 — 입력칸은 로컬 시각을 다루므로 테스트도 로컬로 기대값을 만든다. */
const pad = (value: number) => String(value).padStart(2, '0');
const localInput = (iso: string) => {
  const at = new Date(iso);
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`;
};

const setWindow = (startLocal: string, endLocal: string) => {
  fireEvent.change(screen.getByLabelText('노출 시작'), { target: { value: startLocal } });
  fireEvent.change(screen.getByLabelText('노출 종료'), { target: { value: endLocal } });
};

describe('SitePopupPage — 목록', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.list.mockResolvedValue([popup()]);
  });

  it('팝업과 노출 순서를 보여 준다', async () => {
    render(<SitePopupPage />);

    expect(await screen.findByText('추석 휴진 안내')).toBeInTheDocument();
    expect(screen.getByTestId('state-p1')).toHaveTextContent('노출 중');
  });

  it('예약·종료·꺼짐이 서로 다른 상태로 보인다 — 안 뜨는 이유가 셋 다 다르다', async () => {
    mocked.list.mockResolvedValue([
      popup({ id: 'a', visible: false, scheduled: true }),
      popup({ id: 'b', visible: false, scheduled: false }),
      popup({ id: 'c', active: false, visible: false }),
    ]);

    render(<SitePopupPage />);

    expect(await screen.findByTestId('state-a')).toHaveTextContent('예약');
    expect(screen.getByTestId('state-b')).toHaveTextContent('종료');
    expect(screen.getByTestId('state-c')).toHaveTextContent('꺼짐');
  });

  it('꺼진 팝업의 버튼은 올리기다', async () => {
    mocked.list.mockResolvedValue([popup({ active: false, visible: false })]);

    render(<SitePopupPage />);

    expect(await screen.findByRole('button', { name: '올리기' })).toBeInTheDocument();
  });

  it('서버가 준 순서를 그대로 그린다 — 화면이 다시 정렬하지 않는다', async () => {
    mocked.list.mockResolvedValue([
      popup({ id: 'a', title: '첫째', sortOrder: 1 }),
      popup({ id: 'b', title: '둘째', sortOrder: 2 }),
      popup({ id: 'c', title: '셋째', sortOrder: 3 }),
    ]);

    render(<SitePopupPage />);
    await screen.findByText('첫째');

    const titles = screen.getAllByRole('row').slice(1).map((row) => row.children[1].textContent);
    expect(titles).toEqual(['첫째', '둘째', '셋째']);
  });

  it('조회 실패는 빈 표가 아니라 경고로 나온다 — 빈 표는 "팝업이 없다"로 위장한다', async () => {
    mocked.list.mockRejectedValue(new Error('boom'));

    render(<SitePopupPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('팝업 목록을 불러오지 못했습니다.');
    expect(screen.queryByTestId('empty')).not.toBeInTheDocument();
  });

  it('결과가 없으면 빈 목록 문구를 보여 준다', async () => {
    mocked.list.mockResolvedValue([]);

    render(<SitePopupPage />);

    expect(await screen.findByTestId('empty')).toBeInTheDocument();
  });

  it('지금 노출 확인은 서버 판정을 그대로 센다 — 브라우저 시계로 계산하지 않는다', async () => {
    const user = userEvent.setup();
    mocked.visible.mockResolvedValue([popup(), popup({ id: 'p2' })]);
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '지금 노출 확인' }));

    expect(await screen.findByTestId('visible-count')).toHaveTextContent('지금 노출 중 2건');
    expect(mocked.visible).toHaveBeenCalledTimes(1);
  });

  it('노출 확인 실패는 경고로 나온다', async () => {
    const user = userEvent.setup();
    mocked.visible.mockRejectedValue(new Error('boom'));
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '지금 노출 확인' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('지금 노출 중인 팝업을 불러오지 못했습니다.');
    expect(screen.queryByTestId('visible-count')).not.toBeInTheDocument();
  });
});

describe('SitePopupPage — 등록·수정', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.list.mockResolvedValue([popup()]);
    mocked.register.mockResolvedValue(popup());
    mocked.update.mockResolvedValue(popup());
  });

  it('제목이 비면 저장하지 않는다', async () => {
    const user = userEvent.setup();
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '팝업 등록' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('팝업 제목은 필수입니다.');
    expect(mocked.register).not.toHaveBeenCalled();
  });

  it('노출 구간이 비면 저장하지 않는다', async () => {
    const user = userEvent.setup();
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.type(screen.getByLabelText('제목'), '신규 팝업');
    await user.click(screen.getByRole('button', { name: '팝업 등록' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('노출 시작과 종료 시각은 필수입니다.');
    expect(mocked.register).not.toHaveBeenCalled();
  });

  it('종료가 시작보다 앞이면 서버까지 가지 않는다 — 그렇게 저장된 팝업은 영영 안 뜨는데 오류도 없다', async () => {
    const user = userEvent.setup();
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.type(screen.getByLabelText('제목'), '신규 팝업');
    setWindow('2026-09-10T09:00', '2026-09-01T09:00');
    await user.click(screen.getByRole('button', { name: '팝업 등록' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('노출 종료는 시작보다 뒤여야 합니다.');
    expect(mocked.register).not.toHaveBeenCalled();
  });

  it('등록은 로컬 입력을 ISO 로 바꿔 보낸다', async () => {
    const user = userEvent.setup();
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.type(screen.getByLabelText('제목'), '신규 팝업');
    await user.type(screen.getByLabelText('링크 주소'), 'https://site/x');
    setWindow('2026-09-01T09:00', '2026-09-30T09:00');
    await user.click(screen.getByRole('button', { name: '팝업 등록' }));

    await waitFor(() => expect(mocked.register).toHaveBeenCalledWith({
      title: '신규 팝업',
      imageUrl: undefined,
      linkUrl: 'https://site/x',
      openInNewWindow: true,
      startsAt: new Date('2026-09-01T09:00').toISOString(),
      endsAt: new Date('2026-09-30T09:00').toISOString(),
      sortOrder: 0,
    }));
  });

  it('등록 실패는 경고로 나온다', async () => {
    const user = userEvent.setup();
    mocked.register.mockRejectedValue(new Error('boom'));
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.type(screen.getByLabelText('제목'), '신규 팝업');
    setWindow('2026-09-01T09:00', '2026-09-30T09:00');
    await user.click(screen.getByRole('button', { name: '팝업 등록' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('팝업 등록에 실패했습니다.');
  });

  it('수정 버튼은 저장된 값을 폼에 되돌려 놓는다', async () => {
    const user = userEvent.setup();
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '수정' }));

    expect(screen.getByLabelText('제목')).toHaveValue('추석 휴진 안내');
    expect(screen.getByLabelText('이미지 주소')).toHaveValue('https://cdn/x.png');
    expect(screen.getByLabelText('노출 시작')).toHaveValue(localInput('2026-09-01T12:00:00Z'));
    expect(screen.getByLabelText('노출 순서')).toHaveValue(1);
  });

  it('수정 저장은 update 를 부른다', async () => {
    const user = userEvent.setup();
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '수정' }));
    await user.clear(screen.getByLabelText('제목'));
    await user.type(screen.getByLabelText('제목'), '바뀐 제목');
    await user.click(screen.getByRole('button', { name: '수정 저장' }));

    await waitFor(() => expect(mocked.update).toHaveBeenCalledWith('p1', expect.objectContaining({
      title: '바뀐 제목',
      sortOrder: 1,
    })));
  });

  it('편집 취소는 폼을 비우고 등록 모드로 돌린다', async () => {
    const user = userEvent.setup();
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '수정' }));
    await user.click(screen.getByRole('button', { name: '편집 취소' }));

    expect(screen.getByLabelText('제목')).toHaveValue('');
    expect(screen.getByRole('button', { name: '팝업 등록' })).toBeInTheDocument();
  });
});

describe('SitePopupPage — 노출·삭제', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.list.mockResolvedValue([popup()]);
    mocked.changeActivation.mockResolvedValue(popup({ active: false }));
    mocked.remove.mockResolvedValue(popup({ deleted: true }));
  });

  it('내리기는 켜짐만 끈다 — 일정은 그대로 남는다', async () => {
    const user = userEvent.setup();
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '내리기' }));

    await waitFor(() => expect(mocked.changeActivation).toHaveBeenCalledWith('p1', false));
  });

  it('올리기는 다시 켠다', async () => {
    const user = userEvent.setup();
    mocked.list.mockResolvedValue([popup({ active: false, visible: false })]);
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '올리기' }));

    await waitFor(() => expect(mocked.changeActivation).toHaveBeenCalledWith('p1', true));
  });

  it('노출 상태 변경 실패는 경고로 나온다', async () => {
    const user = userEvent.setup();
    mocked.changeActivation.mockRejectedValue(new Error('boom'));
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '내리기' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('노출 상태 변경에 실패했습니다.');
  });

  it('삭제하면 목록을 다시 읽는다', async () => {
    const user = userEvent.setup();
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mocked.remove).toHaveBeenCalledWith('p1'));
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(2));
  });

  it('삭제 실패는 경고로 나온다', async () => {
    const user = userEvent.setup();
    mocked.remove.mockRejectedValue(new Error('boom'));
    render(<SitePopupPage />);
    await screen.findByText('추석 휴진 안내');

    await user.click(screen.getByRole('button', { name: '삭제' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('팝업 삭제에 실패했습니다.');
  });
});
