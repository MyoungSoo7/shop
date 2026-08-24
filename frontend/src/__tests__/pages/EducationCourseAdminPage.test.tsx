import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import EducationCourseAdminPage from '@/pages/system/EducationCourseAdminPage';
import { educationApi, type Course, type CoursePage, type Lesson } from '@/api/education';

vi.mock('@/api/education', () => ({
  educationApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    publish: vi.fn(),
    hide: vi.fn(),
    close: vi.fn(),
    lessons: vi.fn(),
    addLesson: vi.fn(),
    reorderLessons: vi.fn(),
  },
}));

const mocked = vi.mocked(educationApi);

const course = (overrides: Partial<Course> = {}): Course => ({
  id: 'c1',
  title: '헥사고날 입문',
  description: null,
  status: 'DRAFT',
  updatedBy: 'admin@lemuel.io',
  version: 1,
  ...overrides,
});

const pageOf = (rows: Course[]): CoursePage => ({
  content: rows,
  totalElements: rows.length,
  totalPages: rows.length === 0 ? 0 : 1,
  number: 0,
  size: 20,
});

const lesson = (id: string, title: string, sequence: number): Lesson => ({
  id, courseId: 'c1', title, sequence, contentType: 'VIDEO', contentRef: 'pending',
});

describe('EducationCourseAdminPage — 과정 목록', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.list.mockResolvedValue(pageOf([]));
  });

  it('첫 진입에 과정을 읽어 그린다', async () => {
    mocked.list.mockResolvedValue(pageOf([course()]));
    render(<EducationCourseAdminPage />);

    expect(await screen.findByRole('button', { name: '헥사고날 입문' })).toBeInTheDocument();
    expect(screen.getByText('DRAFT')).toBeInTheDocument();
  });

  it('조회 실패는 사용자에게 드러낸다 — 빈 표를 성공처럼 보여 주지 않는다', async () => {
    mocked.list.mockRejectedValue(new Error('boom'));
    render(<EducationCourseAdminPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('교육 과정을 불러오지 못했습니다.');
  });
});

describe('EducationCourseAdminPage — 과정 추가', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.list.mockResolvedValue(pageOf([]));
  });

  it('과정명이 비어 있으면 서버를 부르지 않는다 — 빈 과정이 목록에 쌓이지 않게', async () => {
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());

    await user.click(await screen.findByRole('button', { name: '과정 추가' }));
    await user.type(screen.getByLabelText('과정명'), '   ');
    await user.click(screen.getByRole('button', { name: '과정 추가' }));

    expect(mocked.create).not.toHaveBeenCalled();
  });

  it('추가하면 입력칸을 비우고 목록을 다시 읽는다 — 두 번 누르면 같은 과정이 두 개 생긴다', async () => {
    mocked.create.mockResolvedValue(course());
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    const before = mocked.list.mock.calls.length;

    await user.type(await screen.findByLabelText('과정명'), '헥사고날 입문');
    await user.click(screen.getByRole('button', { name: '과정 추가' }));

    await waitFor(() => expect(mocked.create).toHaveBeenCalledWith({ title: '헥사고날 입문' }));
    await waitFor(() => expect(screen.getByLabelText('과정명')).toHaveValue(''));
    expect(mocked.list.mock.calls.length).toBeGreaterThan(before);
  });

  it('추가 실패는 드러낸다', async () => {
    mocked.create.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await waitFor(() => expect(mocked.list).toHaveBeenCalled());

    await user.type(await screen.findByLabelText('과정명'), '헥사고날 입문');
    await user.click(screen.getByRole('button', { name: '과정 추가' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('교육 과정 저장에 실패했습니다.');
  });
});

describe('EducationCourseAdminPage — 게시 상태는 한 방향으로만 흐른다', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.publish.mockResolvedValue(course({ status: 'PUBLISHED' }));
    mocked.hide.mockResolvedValue(course({ status: 'HIDDEN' }));
    mocked.close.mockResolvedValue(course({ status: 'CLOSED' }));
  });

  it('DRAFT 에는 "게시"만 나온다 — 초안을 바로 종료하는 문을 열지 않는다', async () => {
    mocked.list.mockResolvedValue(pageOf([course({ status: 'DRAFT' })]));
    render(<EducationCourseAdminPage />);

    expect(await screen.findByRole('button', { name: '게시' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '숨김' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '종료' })).not.toBeInTheDocument();
  });

  it('PUBLISHED 에는 "숨김"만, HIDDEN 에는 "종료"만 나온다', async () => {
    mocked.list.mockResolvedValue(pageOf([
      course({ id: 'c1', title: '게시된 과정', status: 'PUBLISHED' }),
      course({ id: 'c2', title: '숨긴 과정', status: 'HIDDEN' }),
    ]));
    render(<EducationCourseAdminPage />);

    expect(await screen.findByRole('button', { name: '숨김' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '종료' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '게시' })).not.toBeInTheDocument();
  });

  it('CLOSED 는 되돌리는 버튼이 아예 없다 — 종료는 끝이다', async () => {
    mocked.list.mockResolvedValue(pageOf([course({ status: 'CLOSED' })]));
    render(<EducationCourseAdminPage />);

    await screen.findByText('CLOSED');
    expect(screen.queryByRole('button', { name: '게시' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '숨김' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: '종료' })).not.toBeInTheDocument();
  });

  it('게시는 상태 문자열이 아니라 전용 경로를 부르고 목록을 다시 읽는다', async () => {
    mocked.list.mockResolvedValue(pageOf([course({ status: 'DRAFT' })]));
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await screen.findByRole('button', { name: '게시' });
    const before = mocked.list.mock.calls.length;

    await user.click(screen.getByRole('button', { name: '게시' }));

    await waitFor(() => expect(mocked.publish).toHaveBeenCalledWith('c1'));
    expect(mocked.update).not.toHaveBeenCalled();
    await waitFor(() => expect(mocked.list.mock.calls.length).toBeGreaterThan(before));
  });

  it('숨김·종료도 각자의 경로를 쓴다', async () => {
    mocked.list.mockResolvedValue(pageOf([
      course({ id: 'c1', title: '게시된 과정', status: 'PUBLISHED' }),
      course({ id: 'c2', title: '숨긴 과정', status: 'HIDDEN' }),
    ]));
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);

    await user.click(await screen.findByRole('button', { name: '숨김' }));
    await waitFor(() => expect(mocked.hide).toHaveBeenCalledWith('c1'));

    await user.click(await screen.findByRole('button', { name: '종료' }));
    await waitFor(() => expect(mocked.close).toHaveBeenCalledWith('c2'));
  });

  it('상태 변경 실패는 드러낸다 — 조용히 넘어가면 안 바뀐 걸 바뀐 줄 안다', async () => {
    mocked.list.mockResolvedValue(pageOf([course({ status: 'DRAFT' })]));
    mocked.publish.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);

    await user.click(await screen.findByRole('button', { name: '게시' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('상태 변경에 실패했습니다.');
  });
});

describe('EducationCourseAdminPage — 차시', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.list.mockResolvedValue(pageOf([course()]));
    mocked.lessons.mockResolvedValue([
      lesson('l1', '1차시 오리엔테이션', 1),
      lesson('l2', '2차시 포트와 어댑터', 2),
      lesson('l3', '3차시 도메인 봉인', 3),
    ]);
  });

  const openCourse = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(await screen.findByRole('button', { name: '헥사고날 입문' }));
    await screen.findByRole('list');
  };

  it('과정을 고르면 그 과정의 차시를 순서대로 펼친다', async () => {
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);

    await openCourse(user);

    expect(mocked.lessons).toHaveBeenCalledWith('c1');
    expect(within(screen.getByRole('list')).getAllByRole('listitem').map(li => li.textContent))
      .toEqual([
        '1차시 오리엔테이션 위로',
        '2차시 포트와 어댑터 위로',
        '3차시 도메인 봉인 위로',
      ]);
  });

  it('차시 조회 실패는 드러낸다', async () => {
    mocked.lessons.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);

    await user.click(await screen.findByRole('button', { name: '헥사고날 입문' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('차시를 불러오지 못했습니다.');
  });

  it('과정을 고르지 않았으면 차시 영역 자체가 없다', async () => {
    render(<EducationCourseAdminPage />);
    await screen.findByRole('button', { name: '헥사고날 입문' });

    expect(screen.queryByLabelText('차시명')).not.toBeInTheDocument();
  });

  it('차시명이 비어 있으면 서버를 부르지 않는다', async () => {
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await openCourse(user);

    await user.click(screen.getByRole('button', { name: '차시 추가' }));
    await user.type(screen.getByLabelText('차시명'), '  ');
    await user.click(screen.getByRole('button', { name: '차시 추가' }));

    expect(mocked.addLesson).not.toHaveBeenCalled();
  });

  it('새 차시는 맨 뒤 순번을 받는다 — 기존 차시의 번호를 빼앗지 않는다', async () => {
    mocked.addLesson.mockResolvedValue(lesson('l4', '4차시', 4));
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await openCourse(user);

    await user.type(screen.getByLabelText('차시명'), '4차시 이벤트 드리븐');
    await user.click(screen.getByRole('button', { name: '차시 추가' }));

    await waitFor(() => expect(mocked.addLesson).toHaveBeenCalledWith('c1', {
      title: '4차시 이벤트 드리븐',
      sequence: 4,
      contentType: 'VIDEO',
      contentRef: 'pending',
      required: true,
    }));
    await waitFor(() => expect(screen.getByLabelText('차시명')).toHaveValue(''));
  });

  it('차시 추가 실패는 드러낸다', async () => {
    mocked.addLesson.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await openCourse(user);

    await user.type(screen.getByLabelText('차시명'), '4차시');
    await user.click(screen.getByRole('button', { name: '차시 추가' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('차시 저장에 실패했습니다.');
  });
});

describe('EducationCourseAdminPage — 차시 순서 바꾸기', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocked.list.mockResolvedValue(pageOf([course()]));
    mocked.lessons.mockResolvedValue([
      lesson('l1', '1차시 오리엔테이션', 1),
      lesson('l2', '2차시 포트와 어댑터', 2),
      lesson('l3', '3차시 도메인 봉인', 3),
    ]);
  });

  const openCourse = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.click(await screen.findByRole('button', { name: '헥사고날 입문' }));
    await screen.findByRole('list');
  };

  it('맨 위 차시의 "위로"는 잠겨 있다', async () => {
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await openCourse(user);

    const [first, second] = screen.getAllByRole('button', { name: '위로' });
    expect(first).toBeDisabled();
    expect(second).toBeEnabled();
  });

  it('바로 위 차시와 자리만 맞바꾼 전체 순서를 보낸다 — 한 건씩 옮기면 중간에 번호가 겹친다', async () => {
    mocked.reorderLessons.mockResolvedValue([
      lesson('l1', '1차시 오리엔테이션', 1),
      lesson('l3', '3차시 도메인 봉인', 2),
      lesson('l2', '2차시 포트와 어댑터', 3),
    ]);
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await openCourse(user);

    // 세 번째(l3) 를 위로 → l2 와 자리만 바뀌고 l1 은 그대로여야 한다.
    await user.click(screen.getAllByRole('button', { name: '위로' })[2]);

    await waitFor(() =>
      expect(mocked.reorderLessons).toHaveBeenCalledWith('c1', ['l1', 'l3', 'l2']));
  });

  it('바뀐 순서는 서버 응답으로 다시 그린다 — 화면이 제 마음대로 앞서 그리지 않는다', async () => {
    mocked.reorderLessons.mockResolvedValue([
      lesson('l2', '2차시 포트와 어댑터', 1),
      lesson('l1', '1차시 오리엔테이션', 2),
      lesson('l3', '3차시 도메인 봉인', 3),
    ]);
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await openCourse(user);

    await user.click(screen.getAllByRole('button', { name: '위로' })[1]);

    await waitFor(() =>
      expect(within(screen.getByRole('list')).getAllByRole('listitem')[0])
        .toHaveTextContent('2차시 포트와 어댑터'));
  });

  it('순서 변경 실패는 드러내고 목록은 원래대로 둔다', async () => {
    mocked.reorderLessons.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<EducationCourseAdminPage />);
    await openCourse(user);

    await user.click(screen.getAllByRole('button', { name: '위로' })[1]);

    expect(await screen.findByRole('alert')).toHaveTextContent('차시 순서 변경에 실패했습니다.');
    expect(within(screen.getByRole('list')).getAllByRole('listitem')[0])
      .toHaveTextContent('1차시 오리엔테이션');
  });
});
