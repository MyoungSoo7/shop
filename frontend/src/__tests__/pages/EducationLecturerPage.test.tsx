import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import EducationLecturerPage from '@/pages/system/EducationLecturerPage';
import { educationApi, type Course, type CoursePage } from '@/api/education';
import {
  lecturerApi,
  type Lecturer,
  type LecturerAssignment,
  type LecturerPage,
} from '@/api/educationLecturer';

vi.mock('@/api/education', () => ({
  educationApi: { list: vi.fn() },
}));

vi.mock('@/api/educationLecturer', () => ({
  lecturerApi: {
    list: vi.fn(),
    get: vi.fn(),
    register: vi.fn(),
    update: vi.fn(),
    changeActivation: vi.fn(),
    remove: vi.fn(),
    assignments: vi.fn(),
    assign: vi.fn(),
    unassign: vi.fn(),
    byCourse: vi.fn(),
  },
}));

const courses = vi.mocked(educationApi);
const mocked = vi.mocked(lecturerApi);

const course = (overrides: Partial<Course> = {}): Course => ({
  id: 'c1',
  title: '정산 교육',
  description: null,
  status: 'PUBLISHED',
  updatedBy: 'admin@lemuel.io',
  version: 1,
  ...overrides,
});

const coursePage = (rows: Course[]): CoursePage => ({
  content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 100,
});

const lecturer = (overrides: Partial<Lecturer> = {}): Lecturer => ({
  id: 'l1',
  name: '김강사',
  englishName: 'Kim',
  graduateSchool: 'OO대학원',
  officeName: 'OO치과',
  career: '10년',
  lecturerType: '외부 강사',
  historyKo: '약력',
  historyEn: 'history',
  etcMemo: '메모',
  majors: ['보철', '임플란트'],
  lectureFields: ['보철 실습'],
  active: true,
  deleted: false,
  deletedAt: null,
  updatedBy: 'admin@lemuel.io',
  version: 0,
  ...overrides,
});

const lecturerPage = (rows: Lecturer[]): LecturerPage => ({
  content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 20,
});

const assignment = (overrides: Partial<LecturerAssignment> = {}): LecturerAssignment => ({
  id: 'a1',
  courseId: 'c1',
  courseTitle: '정산 교육',
  lecturerId: 'l1',
  lecturerName: '김강사',
  assignedAt: '2026-08-20T01:00:00Z',
  assignedBy: 'admin@lemuel.io',
  ...overrides,
});

describe('EducationLecturerPage — 목록', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    courses.list.mockResolvedValue(coursePage([course()]));
    mocked.list.mockResolvedValue(lecturerPage([lecturer()]));
  });

  it('강사와 전공을 보여 준다', async () => {
    render(<EducationLecturerPage />);

    expect(await screen.findByText('김강사')).toBeInTheDocument();
    expect(screen.getByText('OO치과')).toBeInTheDocument();
    expect(screen.getByText('보철, 임플란트')).toBeInTheDocument();
    expect(screen.getByTestId('state-l1')).toHaveTextContent('활성');
  });

  it('휴식 중인 강사는 상태가 다르게 보인다 — 배정 후보인지 한눈에 갈려야 한다', async () => {
    mocked.list.mockResolvedValue(lecturerPage([lecturer({ active: false })]));

    render(<EducationLecturerPage />);

    expect(await screen.findByTestId('state-l1')).toHaveTextContent('휴식');
    expect(screen.getByRole('button', { name: '활성화' })).toBeInTheDocument();
  });

  it('전공이 없으면 빈칸 대신 하이픈이다', async () => {
    mocked.list.mockResolvedValue(lecturerPage([lecturer({ majors: [], officeName: null, lecturerType: null })]));

    render(<EducationLecturerPage />);

    await screen.findByText('김강사');
    expect(screen.getAllByText('-')).toHaveLength(3);
  });

  it('조회 실패는 빈 표가 아니라 경고로 나온다 — 빈 표는 "강사가 없다"로 위장한다', async () => {
    mocked.list.mockRejectedValue(new Error('boom'));

    render(<EducationLecturerPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('강사 목록을 불러오지 못했습니다.');
    expect(screen.queryByTestId('empty')).not.toBeInTheDocument();
  });

  it('결과가 없으면 빈 목록 문구를 보여 준다', async () => {
    mocked.list.mockResolvedValue(lecturerPage([]));

    render(<EducationLecturerPage />);

    expect(await screen.findByTestId('empty')).toBeInTheDocument();
  });

  it('검색어는 조회 버튼을 눌러야 반영된다 — 타이핑마다 부르면 늦은 응답이 최신 결과를 덮는다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.type(screen.getByLabelText('검색어'), '김');
    expect(mocked.list).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: '조회' }));
    await waitFor(() => expect(mocked.list).toHaveBeenCalledWith({ keyword: '김', activeOnly: false }));
  });

  it('활성 강사만 체크는 즉시 다시 조회한다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.click(screen.getByLabelText('활성 강사만'));

    await waitFor(() => expect(mocked.list).toHaveBeenCalledWith({ keyword: '', activeOnly: true }));
  });
});

describe('EducationLecturerPage — 등록·수정', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    courses.list.mockResolvedValue(coursePage([course()]));
    mocked.list.mockResolvedValue(lecturerPage([lecturer()]));
    mocked.register.mockResolvedValue(lecturer({ id: 'l2', name: '이강사' }));
    mocked.update.mockResolvedValue(lecturer({ name: '김강사2' }));
  });

  it('쉼표로 적은 분야를 배열로 잘라 보낸다 — 공백은 버린다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.type(screen.getByLabelText('이름'), '이강사');
    await user.type(screen.getByLabelText('전공'), '보철, 교정 ,  ');
    await user.type(screen.getByLabelText('강의 분야'), '보철 실습');
    await user.click(screen.getByRole('button', { name: '강사 등록' }));

    await waitFor(() => expect(mocked.register).toHaveBeenCalledWith(
      expect.objectContaining({ name: '이강사', majors: ['보철', '교정'], lectureFields: ['보철 실습'] }),
    ));
  });

  it('이름이 비면 서버를 부르지 않는다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.type(screen.getByLabelText('소속'), 'OO치과');
    await user.click(screen.getByRole('button', { name: '강사 등록' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('강사 이름은 필수입니다.');
    expect(mocked.register).not.toHaveBeenCalled();
  });

  it('수정 버튼은 폼을 그 강사로 채우고 저장은 update 로 간다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.click(screen.getByRole('button', { name: '수정' }));
    expect(screen.getByLabelText('이름')).toHaveValue('김강사');
    expect(screen.getByLabelText('전공')).toHaveValue('보철, 임플란트');

    await user.click(screen.getByRole('button', { name: '수정 저장' }));

    await waitFor(() => expect(mocked.update).toHaveBeenCalledWith('l1', expect.objectContaining({ name: '김강사' })));
    expect(mocked.register).not.toHaveBeenCalled();
  });

  it('편집 취소는 폼을 비운다 — 남아 있으면 다음 등록이 남의 값으로 나간다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.click(screen.getByRole('button', { name: '수정' }));
    await user.click(screen.getByRole('button', { name: '편집 취소' }));

    expect(screen.getByLabelText('이름')).toHaveValue('');
    expect(screen.getByRole('button', { name: '강사 등록' })).toBeInTheDocument();
  });

  it('빈 선택 항목은 undefined 로 보낸다 — 빈 문자열을 저장하면 "미입력"과 구분이 사라진다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.type(screen.getByLabelText('이름'), '이강사');
    await user.click(screen.getByRole('button', { name: '강사 등록' }));

    await waitFor(() => expect(mocked.register).toHaveBeenCalled());
    expect(mocked.register.mock.calls[0][0].officeName).toBeUndefined();
    expect(mocked.register.mock.calls[0][0].majors).toEqual([]);
  });

  it('등록 실패는 경고로 알린다', async () => {
    const user = userEvent.setup();
    mocked.register.mockRejectedValue(new Error('boom'));
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.type(screen.getByLabelText('이름'), '이강사');
    await user.click(screen.getByRole('button', { name: '강사 등록' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('강사 등록에 실패했습니다.');
  });
});

describe('EducationLecturerPage — 활성·삭제', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    courses.list.mockResolvedValue(coursePage([course()]));
    mocked.list.mockResolvedValue(lecturerPage([lecturer()]));
    mocked.changeActivation.mockResolvedValue(lecturer({ active: false }));
    mocked.remove.mockResolvedValue(lecturer({ active: false, deleted: true }));
  });

  it('휴식 처리는 active=false 로 보낸다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.click(screen.getByRole('button', { name: '휴식 처리' }));

    await waitFor(() => expect(mocked.changeActivation).toHaveBeenCalledWith('l1', false));
  });

  it('삭제는 목록을 다시 부른다 — 지운 강사가 화면에 남으면 다시 배정할 수 있어 보인다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.click(screen.getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mocked.remove).toHaveBeenCalledWith('l1'));
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(2));
  });

  it('삭제 실패는 경고로 알린다', async () => {
    const user = userEvent.setup();
    mocked.remove.mockRejectedValue(new Error('boom'));
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.click(screen.getByRole('button', { name: '삭제' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('강사 삭제에 실패했습니다.');
  });
});

describe('EducationLecturerPage — 과정 배정', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    courses.list.mockResolvedValue(coursePage([course()]));
    mocked.list.mockResolvedValue(lecturerPage([lecturer()]));
    mocked.assignments.mockResolvedValue([assignment()]);
    mocked.assign.mockResolvedValue(assignment({ id: 'a2', courseId: 'c1' }));
    mocked.unassign.mockResolvedValue(undefined);
  });

  it('배정 과정을 열면 그 강사의 과정 목록이 나온다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.click(screen.getByRole('button', { name: '배정 과정' }));

    expect(await screen.findByTestId('assignment-panel')).toHaveTextContent('김강사 배정 과정');
    expect(await screen.findByTestId('assignment-c1')).toHaveTextContent('정산 교육');
  });

  it('배정된 과정이 없으면 그렇다고 말한다', async () => {
    const user = userEvent.setup();
    mocked.assignments.mockResolvedValue([]);
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');

    await user.click(screen.getByRole('button', { name: '배정 과정' }));

    expect(await screen.findByTestId('assignments-empty')).toBeInTheDocument();
  });

  it('과정을 고르지 않고 배정을 누르면 서버를 부르지 않는다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');
    await user.click(screen.getByRole('button', { name: '배정 과정' }));
    await screen.findByTestId('assignment-panel');

    await user.click(screen.getByRole('button', { name: '배정' }));

    expect(mocked.assign).not.toHaveBeenCalled();
  });

  it('과정을 골라 배정하면 목록을 다시 읽는다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');
    await user.click(screen.getByRole('button', { name: '배정 과정' }));
    await screen.findByTestId('assignment-panel');

    await user.selectOptions(screen.getByLabelText('배정 과정'), 'c1');
    await user.click(screen.getByRole('button', { name: '배정' }));

    await waitFor(() => expect(mocked.assign).toHaveBeenCalledWith('l1', 'c1'));
    await waitFor(() => expect(mocked.assignments).toHaveBeenCalledTimes(2));
  });

  it('중복 배정 실패는 이유까지 알려 준다 — 409 를 그냥 "실패"로 적으면 운영자가 손쓸 데가 없다', async () => {
    const user = userEvent.setup();
    mocked.assign.mockRejectedValue(new Error('conflict'));
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');
    await user.click(screen.getByRole('button', { name: '배정 과정' }));
    await screen.findByTestId('assignment-panel');

    await user.selectOptions(screen.getByLabelText('배정 과정'), 'c1');
    await user.click(screen.getByRole('button', { name: '배정' }));

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('이미 배정됐거나 쉬는 강사인지 확인하세요.');
  });

  it('해제는 과정 id 로 보낸다 — 배정 id 가 아니다', async () => {
    const user = userEvent.setup();
    render(<EducationLecturerPage />);
    await screen.findByText('김강사');
    await user.click(screen.getByRole('button', { name: '배정 과정' }));
    await screen.findByTestId('assignment-c1');

    await user.click(screen.getByRole('button', { name: '해제' }));

    await waitFor(() => expect(mocked.unassign).toHaveBeenCalledWith('l1', 'c1'));
  });
});
