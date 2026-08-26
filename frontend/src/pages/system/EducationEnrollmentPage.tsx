import { useCallback, useEffect, useState } from 'react';
import { educationApi, type Course } from '@/api/education';
import {
  enrollmentApi,
  type CapacitySummary,
  type Enrollment,
  type EnrollmentStatus,
} from '@/api/educationEnrollment';

const STATUS_LABEL: Record<EnrollmentStatus, string> = {
  WAITING: '대기',
  CONFIRMED: '신청완료',
  CANCELLED: '취소',
};

/** 정원 없음(null)과 0 을 같은 모양으로 그리면 안 된다 — 0 은 마감이고 null 은 제한이 없다는 뜻이다. */
const capacityText = (value: number | null) => (value === null ? '없음' : String(value));

const dateText = (value?: string | null) =>
  value ? new Date(value).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' }) : '-';

export default function EducationEnrollmentPage() {
  const [courses, setCourses] = useState<Course[]>([]);
  const [courseId, setCourseId] = useState('');
  const [status, setStatus] = useState<'' | EnrollmentStatus>('');
  const [keyword, setKeyword] = useState('');
  // 입력칸과 <b>실제로 조회에 쓰인 검색어</b>를 나눠 둔다. 한 글자마다 서버를 부르면
  // 타이핑 도중의 응답이 뒤늦게 도착해 마지막 결과를 덮어쓴다.
  const [query, setQuery] = useState('');
  const [rows, setRows] = useState<Enrollment[] | null>(null);
  const [summary, setSummary] = useState<CapacitySummary | null>(null);
  const [capacityInput, setCapacityInput] = useState('');
  const [applicantId, setApplicantId] = useState('');
  const [applicantName, setApplicantName] = useState('');
  const [applicantOrganization, setApplicantOrganization] = useState('');
  const [cancelTarget, setCancelTarget] = useState<string | null>(null);
  const [cancelReason, setCancelReason] = useState('');
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setCourses((await educationApi.list({ size: 100 })).content);
      } catch {
        setError('교육 과정을 불러오지 못했습니다.');
      }
    })();
  }, []);

  const loadRows = useCallback(async () => {
    try {
      const page = await enrollmentApi.list({
        courseId: courseId || undefined,
        status: status || undefined,
        keyword: query,
      });
      setRows(page.content);
      setError(null);
    } catch {
      // 빈 표를 그리면 조회 실패가 "신청자가 없다"로 위장한다.
      setRows(null);
      setError('수강 신청을 불러오지 못했습니다.');
    }
  }, [courseId, status, query]);

  const loadSummary = useCallback(async () => {
    if (!courseId) {
      setSummary(null);
      setCapacityInput('');
      return;
    }
    try {
      const next = await enrollmentApi.summary(courseId);
      setSummary(next);
      setCapacityInput(next.capacity === null ? '' : String(next.capacity));
    } catch {
      setSummary(null);
      setError('정원 현황을 불러오지 못했습니다.');
    }
  }, [courseId]);

  useEffect(() => { void loadRows(); }, [loadRows]);
  useEffect(() => { void loadSummary(); }, [loadSummary]);

  const refresh = async () => { await loadRows(); await loadSummary(); };

  const saveCapacity = async () => {
    if (!courseId) return;
    const trimmed = capacityInput.trim();
    // 빈 칸은 "정원 없음"이다 — 0 으로 보내면 모든 확정이 막힌다.
    const next = trimmed === '' ? null : Number(trimmed);
    if (next !== null && (!Number.isInteger(next) || next < 0)) {
      setError('정원은 0 이상의 정수여야 합니다.');
      return;
    }
    try {
      const updated = await enrollmentApi.changeCapacity(courseId, next);
      setSummary(updated);
      setError(null);
    } catch {
      setError('정원 변경에 실패했습니다. 확정 인원보다 작게 줄일 수는 없습니다.');
    }
  };

  const register = async () => {
    if (!courseId || !applicantId.trim() || !applicantName.trim()) return;
    try {
      await enrollmentApi.register({
        courseId,
        applicantId: applicantId.trim(),
        applicantName: applicantName.trim(),
        applicantOrganization: applicantOrganization.trim() || undefined,
      });
      setApplicantId('');
      setApplicantName('');
      setApplicantOrganization('');
      await refresh();
    } catch {
      setError('수강 신청 등록에 실패했습니다.');
    }
  };

  const confirm = async (id: string) => {
    try {
      await enrollmentApi.confirm(id);
      await refresh();
    } catch {
      setError('확정에 실패했습니다. 정원이 찼는지 확인하세요.');
    }
  };

  const cancel = async () => {
    if (!cancelTarget || !cancelReason.trim()) return;
    try {
      await enrollmentApi.cancel(cancelTarget, cancelReason.trim());
      setCancelTarget(null);
      setCancelReason('');
      await refresh();
    } catch {
      setError('취소에 실패했습니다.');
    }
  };

  return (
    <main className="space-y-6 p-6">
      <div>
        <h1 className="text-2xl font-bold">수강 신청 관리</h1>
        <p className="text-sm text-gray-500">과정별 신청자와 정원·대기·취소를 관리합니다.</p>
      </div>

      {error && <div role="alert" className="text-red-600">{error}</div>}

      <div className="flex flex-wrap items-end gap-2">
        <label className="flex flex-col text-sm">
          과정
          <select
            aria-label="과정"
            value={courseId}
            onChange={(e) => setCourseId(e.target.value)}
            className="rounded border px-3 py-2"
          >
            <option value="">전체 과정</option>
            {courses.map((course) => (
              <option key={course.id} value={course.id}>{course.title}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col text-sm">
          상태
          <select
            aria-label="상태"
            value={status}
            onChange={(e) => setStatus(e.target.value as '' | EnrollmentStatus)}
            className="rounded border px-3 py-2"
          >
            <option value="">전체 상태</option>
            <option value="WAITING">대기</option>
            <option value="CONFIRMED">신청완료</option>
            <option value="CANCELLED">취소</option>
          </select>
        </label>
        <label className="flex flex-col text-sm">
          검색어
          <input
            aria-label="검색어"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            placeholder="이름 또는 소속"
            className="rounded border px-3 py-2"
          />
        </label>
        <button onClick={() => setQuery(keyword)} className="rounded bg-slate-700 px-4 py-2 text-white">조회</button>
      </div>

      {summary && (
        <section data-testid="capacity-summary" className="space-y-2 rounded border p-4">
          <h2 className="text-lg font-semibold">{summary.courseTitle} 정원 현황</h2>
          <dl className="flex flex-wrap gap-6 text-sm">
            <div><dt className="text-gray-500">정원</dt><dd data-testid="summary-capacity">{capacityText(summary.capacity)}</dd></div>
            <div><dt className="text-gray-500">잔여</dt><dd data-testid="summary-remaining">{capacityText(summary.remaining)}</dd></div>
            <div><dt className="text-gray-500">신청완료</dt><dd data-testid="summary-confirmed">{summary.confirmed}</dd></div>
            <div><dt className="text-gray-500">대기</dt><dd data-testid="summary-waiting">{summary.waiting}</dd></div>
            <div><dt className="text-gray-500">취소</dt><dd data-testid="summary-cancelled">{summary.cancelled}</dd></div>
          </dl>
          <div className="flex items-end gap-2">
            <label className="flex flex-col text-sm">
              정원
              <input
                aria-label="정원"
                value={capacityInput}
                onChange={(e) => setCapacityInput(e.target.value)}
                placeholder="비우면 정원 없음"
                className="rounded border px-3 py-2"
              />
            </label>
            <button onClick={() => void saveCapacity()} className="rounded bg-blue-600 px-4 py-2 text-white">정원 저장</button>
          </div>
        </section>
      )}

      {courseId && (
        <section className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col text-sm">
            신청자 ID
            <input aria-label="신청자 ID" value={applicantId} onChange={(e) => setApplicantId(e.target.value)} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            신청자명
            <input aria-label="신청자명" value={applicantName} onChange={(e) => setApplicantName(e.target.value)} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            소속
            <input aria-label="소속" value={applicantOrganization} onChange={(e) => setApplicantOrganization(e.target.value)} className="rounded border px-3 py-2" />
          </label>
          <button onClick={() => void register()} className="rounded bg-blue-600 px-4 py-2 text-white">신청 등록</button>
        </section>
      )}

      {rows && (
        <table className="w-full text-sm">
          <thead>
            <tr>
              <th className="text-left">신청자</th>
              <th className="text-left">소속</th>
              <th>상태</th>
              <th>신청일</th>
              <th className="text-left">비고</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} data-testid={`enrollment-${row.id}`}>
                <td>{row.applicantName}</td>
                <td>{row.applicantOrganization ?? '-'}</td>
                <td data-testid={`status-${row.id}`}>{STATUS_LABEL[row.status]}</td>
                <td>{dateText(row.appliedAt)}</td>
                <td>{row.cancelReason ?? row.adminMemo ?? '-'}</td>
                <td className="space-x-2">
                  {row.status === 'WAITING' && (
                    <button onClick={() => void confirm(row.id)}>확정</button>
                  )}
                  {row.status !== 'CANCELLED' && (
                    <button onClick={() => { setCancelTarget(row.id); setCancelReason(''); }}>취소</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {rows && rows.length === 0 && <p data-testid="empty">신청 내역이 없습니다.</p>}

      {cancelTarget && (
        <section className="flex items-end gap-2 rounded border p-4">
          <label className="flex flex-col text-sm">
            취소 사유
            <input
              aria-label="취소 사유"
              value={cancelReason}
              onChange={(e) => setCancelReason(e.target.value)}
              className="rounded border px-3 py-2"
            />
          </label>
          {/* 사유 없는 취소는 서버가 거절한다 — 운영자 취소와 본인 취소가 구분되지 않기 때문이다. */}
          <button onClick={() => void cancel()} className="rounded bg-red-600 px-4 py-2 text-white">취소 확정</button>
          <button onClick={() => setCancelTarget(null)} className="rounded border px-4 py-2">닫기</button>
        </section>
      )}
    </main>
  );
}
