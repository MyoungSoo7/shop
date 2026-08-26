import { useCallback, useEffect, useState } from 'react';
import { educationApi, type Course } from '@/api/education';
import {
  lecturerApi,
  type Lecturer,
  type LecturerAssignment,
  type LecturerSaveBody,
} from '@/api/educationLecturer';

/** 쉼표로 받은 분야를 정리한다. 서버도 한 번 더 거르지만, 화면이 먼저 정리해야 되돌아온 값과 입력이 어긋나지 않는다. */
const parseFields = (value: string) =>
  value.split(',').map((item) => item.trim()).filter((item) => item.length > 0);

const emptyForm: LecturerSaveBody & { majorsText: string; lectureFieldsText: string } = {
  name: '',
  englishName: '',
  graduateSchool: '',
  officeName: '',
  career: '',
  lecturerType: '',
  historyKo: '',
  historyEn: '',
  etcMemo: '',
  majors: [],
  lectureFields: [],
  majorsText: '',
  lectureFieldsText: '',
};

type FormState = typeof emptyForm;

const toForm = (lecturer: Lecturer): FormState => ({
  name: lecturer.name,
  englishName: lecturer.englishName ?? '',
  graduateSchool: lecturer.graduateSchool ?? '',
  officeName: lecturer.officeName ?? '',
  career: lecturer.career ?? '',
  lecturerType: lecturer.lecturerType ?? '',
  historyKo: lecturer.historyKo ?? '',
  historyEn: lecturer.historyEn ?? '',
  etcMemo: lecturer.etcMemo ?? '',
  majors: lecturer.majors,
  lectureFields: lecturer.lectureFields,
  majorsText: lecturer.majors.join(', '),
  lectureFieldsText: lecturer.lectureFields.join(', '),
});

const toBody = (form: FormState): LecturerSaveBody => ({
  name: form.name.trim(),
  englishName: form.englishName?.trim() || undefined,
  graduateSchool: form.graduateSchool?.trim() || undefined,
  officeName: form.officeName?.trim() || undefined,
  career: form.career?.trim() || undefined,
  lecturerType: form.lecturerType?.trim() || undefined,
  historyKo: form.historyKo?.trim() || undefined,
  historyEn: form.historyEn?.trim() || undefined,
  etcMemo: form.etcMemo?.trim() || undefined,
  majors: parseFields(form.majorsText),
  lectureFields: parseFields(form.lectureFieldsText),
});

const dateText = (value?: string | null) =>
  value ? new Date(value).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' }) : '-';

export default function EducationLecturerPage() {
  const [rows, setRows] = useState<Lecturer[] | null>(null);
  const [courses, setCourses] = useState<Course[]>([]);
  const [keyword, setKeyword] = useState('');
  // 입력칸과 실제 조회에 쓰인 검색어를 나눠 둔다 — 타이핑마다 조회하면 늦게 온 응답이 최신 결과를 덮는다.
  const [query, setQuery] = useState('');
  const [activeOnly, setActiveOnly] = useState(false);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [selected, setSelected] = useState<Lecturer | null>(null);
  const [assignments, setAssignments] = useState<LecturerAssignment[] | null>(null);
  const [assignCourseId, setAssignCourseId] = useState('');
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
      const page = await lecturerApi.list({ keyword: query, activeOnly });
      setRows(page.content);
      setError(null);
    } catch {
      // 빈 표를 그리면 조회 실패가 "강사가 없다"로 위장한다.
      setRows(null);
      setError('강사 목록을 불러오지 못했습니다.');
    }
  }, [query, activeOnly]);

  useEffect(() => { void loadRows(); }, [loadRows]);

  const resetForm = () => { setForm(emptyForm); setEditingId(null); };

  const save = async () => {
    if (!form.name.trim()) {
      setError('강사 이름은 필수입니다.');
      return;
    }
    try {
      if (editingId) {
        await lecturerApi.update(editingId, toBody(form));
      } else {
        await lecturerApi.register(toBody(form));
      }
      resetForm();
      await loadRows();
    } catch {
      setError(editingId ? '강사 수정에 실패했습니다.' : '강사 등록에 실패했습니다.');
    }
  };

  const toggleActivation = async (lecturer: Lecturer) => {
    try {
      await lecturerApi.changeActivation(lecturer.id, !lecturer.active);
      await loadRows();
    } catch {
      setError('활성 상태 변경에 실패했습니다.');
    }
  };

  const remove = async (lecturer: Lecturer) => {
    try {
      await lecturerApi.remove(lecturer.id);
      if (editingId === lecturer.id) resetForm();
      if (selected?.id === lecturer.id) { setSelected(null); setAssignments(null); }
      await loadRows();
    } catch {
      setError('강사 삭제에 실패했습니다.');
    }
  };

  const openAssignments = async (lecturer: Lecturer) => {
    setSelected(lecturer);
    setAssignCourseId('');
    try {
      setAssignments(await lecturerApi.assignments(lecturer.id));
      setError(null);
    } catch {
      setAssignments(null);
      setError('배정 과정을 불러오지 못했습니다.');
    }
  };

  const assign = async () => {
    if (!selected || !assignCourseId) return;
    try {
      await lecturerApi.assign(selected.id, assignCourseId);
      setAssignCourseId('');
      setAssignments(await lecturerApi.assignments(selected.id));
      setError(null);
    } catch {
      setError('배정에 실패했습니다. 이미 배정됐거나 쉬는 강사인지 확인하세요.');
    }
  };

  const unassign = async (courseId: string) => {
    if (!selected) return;
    try {
      await lecturerApi.unassign(selected.id, courseId);
      setAssignments(await lecturerApi.assignments(selected.id));
      setError(null);
    } catch {
      setError('배정 해제에 실패했습니다.');
    }
  };

  return (
    <main className="space-y-6 p-6">
      <div>
        <h1 className="text-2xl font-bold">강사 관리</h1>
        <p className="text-sm text-gray-500">강사 명부와 과정 배정을 관리합니다.</p>
      </div>

      {error && <div role="alert" className="text-red-600">{error}</div>}

      <div className="flex flex-wrap items-end gap-2">
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
        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            aria-label="활성 강사만"
            checked={activeOnly}
            onChange={(e) => setActiveOnly(e.target.checked)}
          />
          활성 강사만
        </label>
        <button onClick={() => setQuery(keyword)} className="rounded bg-slate-700 px-4 py-2 text-white">조회</button>
      </div>

      <section className="space-y-2 rounded border p-4">
        <h2 className="text-lg font-semibold">{editingId ? '강사 수정' : '강사 등록'}</h2>
        <div className="flex flex-wrap gap-2">
          <label className="flex flex-col text-sm">
            이름
            <input aria-label="이름" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            영문명
            <input aria-label="영문명" value={form.englishName} onChange={(e) => setForm({ ...form, englishName: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            소속
            <input aria-label="소속" value={form.officeName} onChange={(e) => setForm({ ...form, officeName: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            출신 대학원
            <input aria-label="출신 대학원" value={form.graduateSchool} onChange={(e) => setForm({ ...form, graduateSchool: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            경력
            <input aria-label="경력" value={form.career} onChange={(e) => setForm({ ...form, career: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            강사 구분
            <input aria-label="강사 구분" value={form.lecturerType} onChange={(e) => setForm({ ...form, lecturerType: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          {/* 분야는 쉼표로 나눠 받는다 — 항목 수가 강사마다 달라 고정 칸으로는 담기지 않는다. */}
          <label className="flex flex-col text-sm">
            전공 (쉼표 구분)
            <input aria-label="전공" value={form.majorsText} onChange={(e) => setForm({ ...form, majorsText: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            강의 분야 (쉼표 구분)
            <input aria-label="강의 분야" value={form.lectureFieldsText} onChange={(e) => setForm({ ...form, lectureFieldsText: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            약력
            <input aria-label="약력" value={form.historyKo} onChange={(e) => setForm({ ...form, historyKo: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            영문 약력
            <input aria-label="영문 약력" value={form.historyEn} onChange={(e) => setForm({ ...form, historyEn: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            비고
            <input aria-label="비고" value={form.etcMemo} onChange={(e) => setForm({ ...form, etcMemo: e.target.value })} className="rounded border px-3 py-2" />
          </label>
        </div>
        <div className="space-x-2">
          <button onClick={() => void save()} className="rounded bg-blue-600 px-4 py-2 text-white">
            {editingId ? '수정 저장' : '강사 등록'}
          </button>
          {editingId && <button onClick={resetForm} className="rounded border px-4 py-2">편집 취소</button>}
        </div>
      </section>

      {rows && (
        <table className="w-full text-sm">
          <thead>
            <tr>
              <th className="text-left">이름</th>
              <th className="text-left">소속</th>
              <th className="text-left">강사 구분</th>
              <th className="text-left">전공</th>
              <th>상태</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} data-testid={`lecturer-${row.id}`}>
                <td>{row.name}</td>
                <td>{row.officeName ?? '-'}</td>
                <td>{row.lecturerType ?? '-'}</td>
                <td>{row.majors.length > 0 ? row.majors.join(', ') : '-'}</td>
                <td data-testid={`state-${row.id}`}>{row.active ? '활성' : '휴식'}</td>
                <td className="space-x-2">
                  <button onClick={() => { setEditingId(row.id); setForm(toForm(row)); }}>수정</button>
                  <button onClick={() => void toggleActivation(row)}>{row.active ? '휴식 처리' : '활성화'}</button>
                  <button onClick={() => void openAssignments(row)}>배정 과정</button>
                  <button onClick={() => void remove(row)}>삭제</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {rows && rows.length === 0 && <p data-testid="empty">등록된 강사가 없습니다.</p>}

      {selected && (
        <section data-testid="assignment-panel" className="space-y-2 rounded border p-4">
          <h2 className="text-lg font-semibold">{selected.name} 배정 과정</h2>
          <div className="flex items-end gap-2">
            <label className="flex flex-col text-sm">
              과정
              <select
                aria-label="배정 과정"
                value={assignCourseId}
                onChange={(e) => setAssignCourseId(e.target.value)}
                className="rounded border px-3 py-2"
              >
                <option value="">과정 선택</option>
                {courses.map((course) => (
                  <option key={course.id} value={course.id}>{course.title}</option>
                ))}
              </select>
            </label>
            <button onClick={() => void assign()} className="rounded bg-blue-600 px-4 py-2 text-white">배정</button>
            <button onClick={() => { setSelected(null); setAssignments(null); }} className="rounded border px-4 py-2">닫기</button>
          </div>
          {assignments && assignments.length === 0 && <p data-testid="assignments-empty">배정된 과정이 없습니다.</p>}
          {assignments && assignments.length > 0 && (
            <ul className="space-y-1">
              {assignments.map((assignment) => (
                <li key={assignment.id} data-testid={`assignment-${assignment.courseId}`} className="flex items-center gap-3">
                  <span>{assignment.courseTitle}</span>
                  <span className="text-gray-500">{dateText(assignment.assignedAt)}</span>
                  <button onClick={() => void unassign(assignment.courseId)}>해제</button>
                </li>
              ))}
            </ul>
          )}
        </section>
      )}
    </main>
  );
}
