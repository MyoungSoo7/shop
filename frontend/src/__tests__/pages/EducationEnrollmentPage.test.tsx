import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import EducationEnrollmentPage from '@/pages/system/EducationEnrollmentPage';
import { educationApi, type Course, type CoursePage } from '@/api/education';
import {
  enrollmentApi,
  type CapacitySummary,
  type Enrollment,
  type EnrollmentPage,
} from '@/api/educationEnrollment';

vi.mock('@/api/education', () => ({
  educationApi: { list: vi.fn() },
}));

vi.mock('@/api/educationEnrollment', () => ({
  enrollmentApi: {
    list: vi.fn(),
    summary: vi.fn(),
    changeCapacity: vi.fn(),
    register: vi.fn(),
    confirm: vi.fn(),
    cancel: vi.fn(),
    correct: vi.fn(),
    memo: vi.fn(),
  },
}));

const courses = vi.mocked(educationApi);
const mocked = vi.mocked(enrollmentApi);

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

const enrollment = (overrides: Partial<Enrollment> = {}): Enrollment => ({
  id: 'e1',
  courseId: 'c1',
  applicantId: 'u-1',
  applicantName: '김운영',
  applicantOrganization: 'OO치과',
  status: 'WAITING',
  adminMemo: null,
  cancelReason: null,
  appliedAt: '2026-08-20T01:00:00Z',
  confirmedAt: null,
  cancelledAt: null,
  updatedBy: 'admin@lemuel.io',
  version: 0,
  ...overrides,
});

const enrollmentPage = (rows: Enrollment[]): EnrollmentPage => ({
  content: rows, totalElements: rows.length, totalPages: 1, number: 0, size: 20,
});

const summary = (overrides: Partial<CapacitySummary> = {}): CapacitySummary => ({
  courseId: 'c1',
  courseTitle: '정산 교육',
  capacity: 30,
  remaining: 2,
  confirmed: 28,
  waiting: 5,
  cancelled: 1,
  ...overrides,
});

const pickCourse = async (user: ReturnType<typeof userEvent.setup>) => {
  // 셀렉트는 마운트부터 있지만 <option> 은 과정 조회가 와야 채워진다. 옵션 전에 고르면
  // 값이 조용히 무시되고 "전체 과정"인 채로 넘어간다.
  await screen.findByRole('option', { name: '정산 교육' });
  await user.selectOptions(await screen.findByLabelText('과정'), 'c1');
  return screen.findByTestId('capacity-summary');
};

describe('EducationEnrollmentPage — 목록', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    courses.list.mockResolvedValue(coursePage([course()]));
    mocked.list.mockResolvedValue(enrollmentPage([]));
    mocked.summary.mockResolvedValue(summary());
  });

  it('첫 진입에 신청 목록을 읽어 그린다', async () => {
    mocked.list.mockResolvedValue(enrollmentPage([enrollment()]));
    render(<EducationEnrollmentPage />);

    expect(await screen.findByText('김운영')).toBeInTheDocument();
    expect(screen.getByText('OO치과')).toBeInTheDocument();
    // 셀렉트의 '대기' 옵션과 구분해서 <b>행의</b> 상태 칸을 본다.
    expect(screen.getByTestId('status-e1')).toHaveTextContent('대기');
  });

  it('과정을 고르지 않았으면 정원 현황을 부르지 않는다 — 부를 대상이 없다', async () => {
    render(<EducationEnrollmentPage />);

    await waitFor(() => expect(mocked.list).toHaveBeenCalled());
    expect(mocked.summary).not.toHaveBeenCalled();
  });

  it('조회 실패는 드러내고 표 자체를 그리지 않는다 — 빈 표는 "신청자 없음"으로 위장한다', async () => {
    mocked.list.mockRejectedValue(new Error('boom'));
    render(<EducationEnrollmentPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('수강 신청을 불러오지 못했습니다.');
    expect(screen.queryByTestId('empty')).not.toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('검색어는 조회를 눌러야 나간다 — 타이핑마다 부르면 늦은 응답이 최신 결과를 덮는다', async () => {
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(1));

    await user.type(await screen.findByLabelText('검색어'), '부산');
    expect(mocked.list).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole('button', { name: '조회' }));

    await waitFor(() => expect(mocked.list).toHaveBeenLastCalledWith({
      courseId: undefined, status: undefined, keyword: '부산',
    }));
  });

  it('상태 필터는 고르는 즉시 서버로 나간다', async () => {
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);

    await user.selectOptions(await screen.findByLabelText('상태'), 'CONFIRMED');

    await waitFor(() => expect(mocked.list).toHaveBeenLastCalledWith({
      courseId: undefined, status: 'CONFIRMED', keyword: '',
    }));
  });
});

describe('EducationEnrollmentPage — 정원 현황', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    courses.list.mockResolvedValue(coursePage([course()]));
    mocked.list.mockResolvedValue(enrollmentPage([]));
    mocked.summary.mockResolvedValue(summary());
  });

  it('과정을 고르면 정원·확정·대기·취소를 함께 보여 준다', async () => {
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);

    await pickCourse(user);

    expect(mocked.summary).toHaveBeenCalledWith('c1');
    expect(await screen.findByTestId('summary-capacity')).toHaveTextContent('30');
    expect(screen.getByTestId('summary-remaining')).toHaveTextContent('2');
    expect(screen.getByTestId('summary-confirmed')).toHaveTextContent('28');
    expect(screen.getByTestId('summary-waiting')).toHaveTextContent('5');
  });

  it('정원 없음은 0 이 아니라 "없음"으로 그린다 — 0 은 마감이라 뜻이 정반대다', async () => {
    mocked.summary.mockResolvedValue(summary({ capacity: null, remaining: null }));
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);

    await pickCourse(user);

    expect(await screen.findByTestId('summary-capacity')).toHaveTextContent('없음');
    expect(screen.getByTestId('summary-remaining')).toHaveTextContent('없음');
    expect(screen.getByLabelText('정원')).toHaveValue('');
  });

  it('정원을 비우고 저장하면 null 을 보낸다 — 0 을 보내면 아무도 확정할 수 없게 된다', async () => {
    const user = userEvent.setup();
    mocked.changeCapacity.mockResolvedValue(summary({ capacity: null, remaining: null }));
    render(<EducationEnrollmentPage />);
    await pickCourse(user);

    await user.clear(await screen.findByLabelText('정원'));
    await user.click(screen.getByRole('button', { name: '정원 저장' }));

    await waitFor(() => expect(mocked.changeCapacity).toHaveBeenCalledWith('c1', null));
  });

  it('숫자가 아니거나 음수인 정원은 서버를 부르지 않는다', async () => {
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);
    await pickCourse(user);

    const input = await screen.findByLabelText('정원');
    await user.clear(input);
    await user.type(input, '-3');
    await user.click(screen.getByRole('button', { name: '정원 저장' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('정원은 0 이상의 정수여야 합니다.');
    expect(mocked.changeCapacity).not.toHaveBeenCalled();
  });

  it('확정 인원보다 작게 줄이려다 거절되면 그 이유를 말한다', async () => {
    mocked.changeCapacity.mockRejectedValue(new Error('conflict'));
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);
    await pickCourse(user);

    await user.click(await screen.findByRole('button', { name: '정원 저장' }));

    expect(await screen.findByRole('alert'))
      .toHaveTextContent('확정 인원보다 작게 줄일 수는 없습니다.');
  });
});

describe('EducationEnrollmentPage — 확정과 취소', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    courses.list.mockResolvedValue(coursePage([course()]));
    mocked.summary.mockResolvedValue(summary());
    mocked.list.mockResolvedValue(enrollmentPage([
      enrollment({ id: 'e1', applicantName: '김대기', status: 'WAITING' }),
      enrollment({ id: 'e2', applicantName: '이확정', status: 'CONFIRMED' }),
      enrollment({ id: 'e3', applicantName: '박취소', status: 'CANCELLED', cancelReason: '본인 요청' }),
    ]));
  });

  it('확정 버튼은 대기 건에만 있다 — 확정된 건을 또 확정하는 문을 열지 않는다', async () => {
    render(<EducationEnrollmentPage />);

    await screen.findByText('김대기');
    expect(screen.getAllByRole('button', { name: '확정' })).toHaveLength(1);
  });

  it('취소된 건에는 취소 버튼이 없다 — 취소는 되돌리지도, 두 번 하지도 않는다', async () => {
    render(<EducationEnrollmentPage />);

    await screen.findByText('박취소');
    expect(screen.getAllByRole('button', { name: '취소' })).toHaveLength(2);
  });

  it('확정하면 목록과 정원 현황을 함께 다시 읽는다 — 잔여가 그대로면 다음 판단이 틀린다', async () => {
    mocked.confirm.mockResolvedValue(enrollment({ status: 'CONFIRMED' }));
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);

    await user.click(await screen.findByRole('button', { name: '확정' }));

    await waitFor(() => expect(mocked.confirm).toHaveBeenCalledWith('e1'));
    await waitFor(() => expect(mocked.list).toHaveBeenCalledTimes(2));
  });

  it('정원이 차서 확정이 거절되면 그렇게 말한다', async () => {
    mocked.confirm.mockRejectedValue(new Error('conflict'));
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);

    await user.click(await screen.findByRole('button', { name: '확정' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('정원이 찼는지 확인하세요.');
  });

  it('사유 없는 취소는 서버를 부르지 않는다 — 운영자 취소와 본인 취소가 구분되지 않는다', async () => {
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);

    await user.click((await screen.findAllByRole('button', { name: '취소' }))[0]);
    await user.click(await screen.findByRole('button', { name: '취소 확정' }));

    expect(mocked.cancel).not.toHaveBeenCalled();
  });

  it('사유를 적어 취소하면 그 사유가 그대로 실려 간다', async () => {
    mocked.cancel.mockResolvedValue(enrollment({ status: 'CANCELLED', cancelReason: '본인 요청' }));
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);

    await user.click((await screen.findAllByRole('button', { name: '취소' }))[0]);
    await user.type(await screen.findByLabelText('취소 사유'), '본인 요청');
    await user.click(screen.getByRole('button', { name: '취소 확정' }));

    await waitFor(() => expect(mocked.cancel).toHaveBeenCalledWith('e1', '본인 요청'));
  });

  it('취소 사유는 목록에 남는다 — 나중에 왜 빠졌는지 묻는 자리가 여기다', async () => {
    render(<EducationEnrollmentPage />);

    expect(await screen.findByText('본인 요청')).toBeInTheDocument();
  });
});

describe('EducationEnrollmentPage — 수기 등록', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    courses.list.mockResolvedValue(coursePage([course()]));
    mocked.list.mockResolvedValue(enrollmentPage([]));
    mocked.summary.mockResolvedValue(summary());
  });

  it('과정을 고르기 전에는 등록 폼이 없다 — 어느 과정에 꽂을지 정해지지 않았다', async () => {
    render(<EducationEnrollmentPage />);

    await screen.findByLabelText('과정');
    expect(screen.queryByLabelText('신청자명')).not.toBeInTheDocument();
  });

  it('필수값이 비면 서버를 부르지 않는다', async () => {
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);
    await pickCourse(user);

    await user.type(await screen.findByLabelText('신청자명'), '김신청');
    await user.click(screen.getByRole('button', { name: '신청 등록' }));

    expect(mocked.register).not.toHaveBeenCalled();
  });

  it('등록하면 입력칸을 비우고 목록을 다시 읽는다 — 두 번 누르면 같은 사람이 두 줄 생긴다', async () => {
    mocked.register.mockResolvedValue(enrollment());
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);
    await pickCourse(user);

    await user.type(await screen.findByLabelText('신청자 ID'), 'u-9');
    await user.type(screen.getByLabelText('신청자명'), '김신청');
    await user.type(screen.getByLabelText('소속'), 'OO치과');
    await user.click(screen.getByRole('button', { name: '신청 등록' }));

    await waitFor(() => expect(mocked.register).toHaveBeenCalledWith({
      courseId: 'c1', applicantId: 'u-9', applicantName: '김신청', applicantOrganization: 'OO치과',
    }));
    await waitFor(() => expect(screen.getByLabelText('신청자명')).toHaveValue(''));
  });

  it('등록 실패는 드러낸다', async () => {
    mocked.register.mockRejectedValue(new Error('boom'));
    const user = userEvent.setup();
    render(<EducationEnrollmentPage />);
    await pickCourse(user);

    await user.type(await screen.findByLabelText('신청자 ID'), 'u-9');
    await user.type(screen.getByLabelText('신청자명'), '김신청');
    await user.click(screen.getByRole('button', { name: '신청 등록' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('수강 신청 등록에 실패했습니다.');
  });
});
