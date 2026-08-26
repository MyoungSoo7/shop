import { useCallback, useEffect, useState } from 'react';
import { popupApi, type Popup, type PopupSaveBody } from '@/api/sitePopup';

/**
 * 사이트 팝업 관리 — dentis 의 admin/site/popup 묶음.
 *
 * <p>상태 칸("노출 중"/"예약"/"종료")은 <b>서버가 준 값</b>을 그대로 쓴다. 시작·종료 시각으로
 * 화면이 직접 계산하면 브라우저 시계로 판정하게 되고, 서버는 안 띄우는데 관리 화면만
 * "노출 중"이라고 말하는 어긋남이 생긴다. 그 어긋남은 오류로 안 보여서 오래 산다.
 */
const pad = (value: number) => String(value).padStart(2, '0');

/** ISO → datetime-local 입력값. 입력칸은 로컬 시각만 다루므로 여기서 한 번만 변환한다. */
const toLocalInput = (iso: string) => {
  const at = new Date(iso);
  return `${at.getFullYear()}-${pad(at.getMonth() + 1)}-${pad(at.getDate())}T${pad(at.getHours())}:${pad(at.getMinutes())}`;
};

const emptyForm = {
  title: '',
  imageUrl: '',
  linkUrl: '',
  openInNewWindow: true,
  startsAt: '',
  endsAt: '',
  sortOrder: 0,
};

type FormState = typeof emptyForm;

const toForm = (popup: Popup): FormState => ({
  title: popup.title,
  imageUrl: popup.imageUrl ?? '',
  linkUrl: popup.linkUrl ?? '',
  openInNewWindow: popup.openInNewWindow,
  startsAt: toLocalInput(popup.startsAt),
  endsAt: toLocalInput(popup.endsAt),
  sortOrder: popup.sortOrder,
});

const toBody = (form: FormState): PopupSaveBody => ({
  title: form.title.trim(),
  imageUrl: form.imageUrl.trim() || undefined,
  linkUrl: form.linkUrl.trim() || undefined,
  openInNewWindow: form.openInNewWindow,
  startsAt: new Date(form.startsAt).toISOString(),
  endsAt: new Date(form.endsAt).toISOString(),
  sortOrder: Number(form.sortOrder) || 0,
});

const dateText = (value?: string | null) =>
  value ? new Date(value).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' }) : '-';

/** 상태 한 칸에 두 축(켜짐/꺼짐 · 일정)을 함께 읽힌다 — 운영자가 묻는 것은 늘 "지금 뜨나"다. */
const stateText = (popup: Popup) => {
  if (popup.deleted) return '삭제';
  if (!popup.active) return '꺼짐';
  if (popup.visible) return '노출 중';
  if (popup.scheduled) return '예약';
  return '종료';
};

export default function SitePopupPage() {
  const [rows, setRows] = useState<Popup[] | null>(null);
  const [form, setForm] = useState<FormState>(emptyForm);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [visibleCount, setVisibleCount] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadRows = useCallback(async () => {
    try {
      setRows(await popupApi.list());
      setError(null);
    } catch {
      // 빈 표를 그리면 조회 실패가 "팝업이 없다"로 위장한다.
      setRows(null);
      setError('팝업 목록을 불러오지 못했습니다.');
    }
  }, []);

  useEffect(() => { void loadRows(); }, [loadRows]);

  const resetForm = () => { setForm(emptyForm); setEditingId(null); };

  const save = async () => {
    if (!form.title.trim()) {
      setError('팝업 제목은 필수입니다.');
      return;
    }
    if (!form.startsAt || !form.endsAt) {
      setError('노출 시작과 종료 시각은 필수입니다.');
      return;
    }
    // 서버도 막지만 화면이 먼저 막는다 — 뒤집힌 구간으로 저장된 팝업은 영영 안 뜨는데 오류도 없다.
    if (new Date(form.endsAt) <= new Date(form.startsAt)) {
      setError('노출 종료는 시작보다 뒤여야 합니다.');
      return;
    }
    try {
      if (editingId) {
        await popupApi.update(editingId, toBody(form));
      } else {
        await popupApi.register(toBody(form));
      }
      resetForm();
      await loadRows();
    } catch {
      setError(editingId ? '팝업 수정에 실패했습니다.' : '팝업 등록에 실패했습니다.');
    }
  };

  const toggleActivation = async (popup: Popup) => {
    try {
      await popupApi.changeActivation(popup.id, !popup.active);
      await loadRows();
    } catch {
      setError('노출 상태 변경에 실패했습니다.');
    }
  };

  const remove = async (popup: Popup) => {
    try {
      await popupApi.remove(popup.id);
      if (editingId === popup.id) resetForm();
      await loadRows();
    } catch {
      setError('팝업 삭제에 실패했습니다.');
    }
  };

  const checkVisible = async () => {
    try {
      setVisibleCount((await popupApi.visible()).length);
      setError(null);
    } catch {
      setVisibleCount(null);
      setError('지금 노출 중인 팝업을 불러오지 못했습니다.');
    }
  };

  return (
    <main className="space-y-6 p-6">
      <div>
        <h1 className="text-2xl font-bold">팝업 관리</h1>
        <p className="text-sm text-gray-500">사이트 팝업의 노출 구간과 순서를 관리합니다.</p>
      </div>

      {error && <div role="alert" className="text-red-600">{error}</div>}

      <div className="flex items-center gap-3">
        <button onClick={() => void checkVisible()} className="rounded bg-slate-700 px-4 py-2 text-white">
          지금 노출 확인
        </button>
        {visibleCount !== null && (
          <span data-testid="visible-count" className="text-sm">지금 노출 중 {visibleCount}건</span>
        )}
      </div>

      <section className="space-y-2 rounded border p-4">
        <h2 className="text-lg font-semibold">{editingId ? '팝업 수정' : '팝업 등록'}</h2>
        <div className="flex flex-wrap gap-2">
          <label className="flex flex-col text-sm">
            제목
            <input aria-label="제목" value={form.title} onChange={(e) => setForm({ ...form, title: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          {/* 이미지는 주소로 받는다 — 게시판 첨부는 글에 매인 구조라 글 없는 팝업이 쓸 수 없다. */}
          <label className="flex flex-col text-sm">
            이미지 주소
            <input aria-label="이미지 주소" value={form.imageUrl} onChange={(e) => setForm({ ...form, imageUrl: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            링크 주소
            <input aria-label="링크 주소" value={form.linkUrl} onChange={(e) => setForm({ ...form, linkUrl: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            노출 시작
            <input aria-label="노출 시작" type="datetime-local" value={form.startsAt} onChange={(e) => setForm({ ...form, startsAt: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            노출 종료
            <input aria-label="노출 종료" type="datetime-local" value={form.endsAt} onChange={(e) => setForm({ ...form, endsAt: e.target.value })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex flex-col text-sm">
            노출 순서
            <input aria-label="노출 순서" type="number" value={form.sortOrder} onChange={(e) => setForm({ ...form, sortOrder: Number(e.target.value) })} className="rounded border px-3 py-2" />
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              aria-label="새 창으로 열기"
              checked={form.openInNewWindow}
              onChange={(e) => setForm({ ...form, openInNewWindow: e.target.checked })}
            />
            새 창으로 열기
          </label>
        </div>
        <div className="space-x-2">
          <button onClick={() => void save()} className="rounded bg-blue-600 px-4 py-2 text-white">
            {editingId ? '수정 저장' : '팝업 등록'}
          </button>
          {editingId && <button onClick={resetForm} className="rounded border px-4 py-2">편집 취소</button>}
        </div>
      </section>

      {rows && (
        <table className="w-full text-sm">
          <thead>
            <tr>
              <th className="text-left">순서</th>
              <th className="text-left">제목</th>
              <th className="text-left">노출 시작</th>
              <th className="text-left">노출 종료</th>
              <th>상태</th>
              <th>관리</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row) => (
              <tr key={row.id} data-testid={`popup-${row.id}`}>
                <td>{row.sortOrder}</td>
                <td>{row.title}</td>
                <td>{dateText(row.startsAt)}</td>
                <td>{dateText(row.endsAt)}</td>
                <td data-testid={`state-${row.id}`}>{stateText(row)}</td>
                <td className="space-x-2">
                  <button onClick={() => { setEditingId(row.id); setForm(toForm(row)); }}>수정</button>
                  <button onClick={() => void toggleActivation(row)}>{row.active ? '내리기' : '올리기'}</button>
                  <button onClick={() => void remove(row)}>삭제</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {rows && rows.length === 0 && <p data-testid="empty">등록된 팝업이 없습니다.</p>}
    </main>
  );
}
