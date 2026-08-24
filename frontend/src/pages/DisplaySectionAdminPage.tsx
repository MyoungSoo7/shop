import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  displaySectionApi,
  type CreateDisplaySectionPayload,
  type DisplaySection,
  type DisplaySectionItem,
  type DisplaySectionKind,
} from '@/api/displaySection';
import { apiErrorMessage } from '@/lib/apiError';
import { useToast } from '@/contexts/useToast';
import Spinner from '@/components/Spinner';

/**
 * 진열 편성(기획전) 운영 콘솔.
 *
 * <p>기획전을 분류 트리로 복제하지 않고 <b>편성</b>으로 다룬다 — 무엇을(상품), 언제(기간),
 * 어떤 순서로(정렬·고정) 보여줄지가 한 화면에 모인다.
 *
 * <p><b>노출 여부를 이 화면이 계산하지 않는다.</b> 운영 목록은 끝난 편성까지 전부 주고, 지금 노출
 * 중인 편성은 서버가 시각으로 판정해 따로 준다. 두 목록을 대조해 뱃지를 그린다 — 화면이 기간을
 * 직접 재면 시계가 어긋난 단말에서 끝난 기획전이 노출중으로 보인다.
 */

const KIND_LABEL: Record<DisplaySectionKind, string> = {
  MAIN: '메인 진열',
  EXHIBITION: '기획전',
  CATEGORY_BEST: '카테고리 베스트',
};

/** 서버의 LocalDateTime(초 포함)을 datetime-local 입력이 받는 분 단위로 자른다 */
const toLocalInput = (value: string | null | undefined) => (value ? value.slice(0, 16) : '');

const periodText = (section: DisplaySection) => {
  const start = toLocalInput(section.startsAt).replace('T', ' ');
  const end = toLocalInput(section.endsAt).replace('T', ' ');
  if (!start && !end) return '상시';
  return `${start || '시작 열림'} ~ ${end || '종료 열림'}`;
};

const blankOrNull = (value: string) => (value.trim() === '' ? null : value);

const DisplaySectionAdminPage: React.FC = () => {
  const { showToast } = useToast();

  const [sections, setSections] = useState<DisplaySection[]>([]);
  const [visibleCodes, setVisibleCodes] = useState<Set<string>>(new Set());
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [selectedCode, setSelectedCode] = useState<string | null>(null);
  const [items, setItems] = useState<DisplaySectionItem[]>([]);
  const [itemsLoading, setItemsLoading] = useState(false);

  // 새 편성 입력
  const [code, setCode] = useState('');
  const [name, setName] = useState('');
  const [kind, setKind] = useState<DisplaySectionKind>('EXHIBITION');
  const [categoryId, setCategoryId] = useState('');
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const [sortOrder, setSortOrder] = useState('');

  // 담기 입력
  const [itemProductId, setItemProductId] = useState('');
  const [itemSortOrder, setItemSortOrder] = useState('');
  const [itemPinned, setItemPinned] = useState(false);

  // 기간 재설정 입력
  const [scheduleStart, setScheduleStart] = useState('');
  const [scheduleEnd, setScheduleEnd] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [all, visible] = await Promise.all([
        displaySectionApi.listAll(),
        displaySectionApi.listVisible(),
      ]);
      setSections(all);
      setVisibleCodes(new Set(visible.map((s) => s.code)));
    } catch (err) {
      setSections([]);
      setError(apiErrorMessage(err, '편성을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const selected = useMemo(
    () => sections.find((s) => s.code === selectedCode) ?? null,
    [sections, selectedCode],
  );

  const loadItems = useCallback(async (sectionCode: string) => {
    setItemsLoading(true);
    try {
      setItems(await displaySectionApi.items(sectionCode));
    } catch (err) {
      setItems([]);
      showToast(apiErrorMessage(err, '편성 내용을 불러오지 못했습니다.'), 'error');
    } finally {
      setItemsLoading(false);
    }
  }, [showToast]);

  const open = useCallback(async (section: DisplaySection) => {
    setSelectedCode(section.code);
    setScheduleStart(toLocalInput(section.startsAt));
    setScheduleEnd(toLocalInput(section.endsAt));
    await loadItems(section.code);
  }, [loadItems]);

  const create = useCallback(async () => {
    if (code.trim() === '' || name.trim() === '') {
      showToast('편성 코드와 이름은 필수입니다.', 'warning');
      return;
    }
    // 서버 도메인도 막지만, 왕복 없이 즉시 알려 준다 — 이 종류만 카테고리를 지목한다
    if (kind === 'CATEGORY_BEST' && categoryId.trim() === '') {
      showToast('카테고리 베스트 편성은 대상 카테고리가 필요합니다.', 'warning');
      return;
    }
    const payload: CreateDisplaySectionPayload = {
      code: code.trim(),
      name: name.trim(),
      kind,
      categoryId: categoryId.trim() === '' ? null : Number(categoryId),
      startsAt: blankOrNull(startsAt),
      endsAt: blankOrNull(endsAt),
      sortOrder: sortOrder.trim() === '' ? 0 : Number(sortOrder),
    };
    try {
      await displaySectionApi.create(payload);
      showToast(`편성 ${payload.code} 을(를) 만들었습니다.`, 'success');
      setCode(''); setName(''); setCategoryId(''); setStartsAt(''); setEndsAt(''); setSortOrder('');
      await load();
    } catch (err) {
      showToast(apiErrorMessage(err, '편성을 만들지 못했습니다.'), 'error');
    }
  }, [code, name, kind, categoryId, startsAt, endsAt, sortOrder, load, showToast]);

  const addItem = useCallback(async () => {
    if (!selected) return;
    const productId = Number(itemProductId);
    if (!Number.isFinite(productId) || productId <= 0) {
      showToast('담을 상품 ID 를 입력하세요.', 'warning');
      return;
    }
    try {
      await displaySectionApi.addItem(selected.code, {
        productId,
        sortOrder: itemSortOrder.trim() === '' ? 0 : Number(itemSortOrder),
        pinned: itemPinned,
      });
      setItemProductId(''); setItemSortOrder(''); setItemPinned(false);
      await loadItems(selected.code);
    } catch (err) {
      showToast(apiErrorMessage(err, '상품을 담지 못했습니다.'), 'error');
    }
  }, [selected, itemProductId, itemSortOrder, itemPinned, loadItems, showToast]);

  const removeItem = useCallback(async (productId: number) => {
    if (!selected) return;
    try {
      await displaySectionApi.removeItem(selected.code, productId);
      await loadItems(selected.code);
    } catch (err) {
      showToast(apiErrorMessage(err, '상품을 빼지 못했습니다.'), 'error');
    }
  }, [selected, loadItems, showToast]);

  const reschedule = useCallback(async () => {
    if (!selected) return;
    try {
      await displaySectionApi.reschedule(selected.code, {
        startsAt: blankOrNull(scheduleStart),
        endsAt: blankOrNull(scheduleEnd),
      });
      showToast('편성 기간을 바꿨습니다.', 'success');
      await load();
    } catch (err) {
      showToast(apiErrorMessage(err, '기간을 바꾸지 못했습니다.'), 'error');
    }
  }, [selected, scheduleStart, scheduleEnd, load, showToast]);

  const toggleActive = useCallback(async (section: DisplaySection) => {
    try {
      await displaySectionApi.setActive(section.code, !section.active);
      await load();
    } catch (err) {
      showToast(apiErrorMessage(err, '노출 상태를 바꾸지 못했습니다.'), 'error');
    }
  }, [load, showToast]);

  return (
    <div>
      <div className="mb-6">
        <h2 className="text-xl font-bold text-gray-900">진열 편성</h2>
        <p className="text-sm text-gray-500 mt-0.5">
          메인 진열·기획전·카테고리 베스트를 한 자리에서 편성합니다. 노출 여부는 서버가 활성 여부와
          기간을 함께 보고 판정하므로, 이 목록의 뱃지도 서버 판정을 그대로 보여 줍니다.
        </p>
      </div>

      <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
        <h3 className="font-bold text-gray-900 text-sm mb-3">새 편성</h3>
        <div className="flex flex-wrap items-end gap-3">
          <label className="block">
            <span className="text-[11px] text-gray-500">편성 코드</span>
            <input
              value={code}
              onChange={(e) => setCode(e.target.value.toUpperCase())}
              placeholder="EXH_2026_SUMMER"
              className="input w-52 font-mono"
            />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">편성 이름</span>
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="2026 여름 기획전"
              className="input w-52"
            />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">종류</span>
            <select value={kind} onChange={(e) => setKind(e.target.value as DisplaySectionKind)} className="input w-40">
              {(Object.keys(KIND_LABEL) as DisplaySectionKind[]).map((k) => (
                <option key={k} value={k}>{KIND_LABEL[k]}</option>
              ))}
            </select>
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">대상 카테고리 ID</span>
            <input
              value={categoryId}
              onChange={(e) => setCategoryId(e.target.value)}
              placeholder="베스트만"
              inputMode="numeric"
              disabled={kind !== 'CATEGORY_BEST'}
              className="input w-28 font-mono disabled:bg-gray-50 disabled:text-gray-400"
            />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">시작 시각</span>
            <input type="datetime-local" value={startsAt} onChange={(e) => setStartsAt(e.target.value)} className="input" />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">종료 시각</span>
            <input type="datetime-local" value={endsAt} onChange={(e) => setEndsAt(e.target.value)} className="input" />
          </label>
          <label className="block">
            <span className="text-[11px] text-gray-500">정렬 순서</span>
            <input
              value={sortOrder}
              onChange={(e) => setSortOrder(e.target.value)}
              placeholder="0"
              inputMode="numeric"
              className="input w-20 font-mono"
            />
          </label>
          <button
            onClick={() => void create()}
            className="px-4 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800"
          >
            편성 만들기
          </button>
        </div>
        <p className="text-[11px] text-gray-400 mt-2">
          기간을 비우면 그 방향은 열려 있습니다(상시 진열). 카테고리는 카테고리 베스트 편성만 지목합니다.
        </p>
      </section>

      {loading && <div className="flex justify-center py-6"><Spinner /></div>}
      {error && (
        <p className="text-sm text-orange-700 bg-orange-50 border border-orange-100 rounded px-3 py-2 mb-6">{error}</p>
      )}

      <section className="bg-white rounded-xl border border-gray-200 p-4 mb-6">
        <h3 className="font-bold text-gray-900 text-sm mb-3">편성 {sections.length}건</h3>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 border-b border-gray-200">
              <tr>
                {['코드', '이름', '종류', '카테고리', '기간', '정렬', '상태', ''].map((h) => (
                  <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500 whitespace-nowrap">{h}</th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-100">
              {sections.map((section) => {
                const visible = visibleCodes.has(section.code);
                return (
                  <tr key={section.code} className={selectedCode === section.code ? 'bg-gray-50' : ''}>
                    <td className="px-3 py-2 font-mono text-xs">{section.code}</td>
                    <td className="px-3 py-2 font-medium text-gray-900">{section.name}</td>
                    <td className="px-3 py-2 text-gray-600 whitespace-nowrap">{KIND_LABEL[section.kind]}</td>
                    <td className="px-3 py-2 font-mono text-xs text-gray-500">{section.categoryId ?? '-'}</td>
                    <td className="px-3 py-2 text-xs text-gray-500 whitespace-nowrap">{periodText(section)}</td>
                    <td className="px-3 py-2 font-mono text-xs text-gray-500">{section.sortOrder}</td>
                    <td className="px-3 py-2">
                      <span className={`text-xs px-2 py-0.5 rounded-full font-semibold ${
                        visible ? 'bg-green-100 text-green-800' : 'bg-gray-200 text-gray-600'
                      }`}>
                        {visible ? '노출중' : '비노출'}
                      </span>
                      {!visible && section.active && (
                        <span className="ml-1 text-[11px] text-gray-400">기간 밖</span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right whitespace-nowrap">
                      <button
                        onClick={() => void open(section)}
                        className="px-2 py-1 rounded border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50"
                      >
                        열기
                      </button>
                      <button
                        onClick={() => void toggleActive(section)}
                        className="ml-2 px-2 py-1 rounded border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50"
                      >
                        {section.active ? '노출 중지' : '노출 재개'}
                      </button>
                    </td>
                  </tr>
                );
              })}
              {!loading && sections.length === 0 && (
                <tr>
                  <td colSpan={8} className="px-3 py-8 text-center text-gray-400">
                    등록된 편성이 없습니다. 위에서 첫 편성을 만들어 주세요.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {selected && (
        <section className="bg-white rounded-xl border border-gray-200 p-4">
          <h3 className="font-bold text-gray-900 text-sm mb-3">
            {selected.name} 편성 내용 <span className="font-mono text-xs text-gray-400">{selected.code}</span>
          </h3>

          <div className="flex flex-wrap items-end gap-3 mb-4 pb-4 border-b border-gray-100">
            <label className="block">
              <span className="text-[11px] text-gray-500">기간 시작</span>
              <input type="datetime-local" value={scheduleStart} onChange={(e) => setScheduleStart(e.target.value)} className="input" />
            </label>
            <label className="block">
              <span className="text-[11px] text-gray-500">기간 종료</span>
              <input type="datetime-local" value={scheduleEnd} onChange={(e) => setScheduleEnd(e.target.value)} className="input" />
            </label>
            <button
              onClick={() => void reschedule()}
              className="px-3 py-2 rounded-lg border border-gray-200 text-sm font-semibold text-gray-700 hover:bg-gray-50"
            >
              기간 저장
            </button>
            <span className="text-[11px] text-gray-400">기간을 다시 잡으면 끝난 편성도 되살아납니다.</span>
          </div>

          <div className="flex flex-wrap items-end gap-3 mb-4">
            <label className="block">
              <span className="text-[11px] text-gray-500">상품 ID</span>
              <input
                value={itemProductId}
                onChange={(e) => setItemProductId(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') void addItem(); }}
                placeholder="예: 101"
                inputMode="numeric"
                className="input w-28 font-mono"
              />
            </label>
            <label className="block">
              <span className="text-[11px] text-gray-500">표시 순서</span>
              <input
                value={itemSortOrder}
                onChange={(e) => setItemSortOrder(e.target.value)}
                placeholder="0"
                inputMode="numeric"
                className="input w-20 font-mono"
              />
            </label>
            <label className="flex items-center gap-2 pb-2">
              <input type="checkbox" checked={itemPinned} onChange={(e) => setItemPinned(e.target.checked)} />
              <span className="text-xs text-gray-600">맨 앞 고정</span>
            </label>
            <button
              onClick={() => void addItem()}
              className="px-3 py-2 rounded-lg bg-gray-900 text-white text-sm font-semibold hover:bg-gray-800"
            >
              상품 담기
            </button>
          </div>

          {itemsLoading && <div className="flex justify-center py-4"><Spinner /></div>}

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  {['상품 ID', '표시 순서', '고정', ''].map((h) => (
                    <th key={h} className="px-3 py-2 text-left text-xs font-semibold text-gray-500">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {items.map((item) => (
                  <tr key={item.productId} data-testid="section-item-row">
                    <td className="px-3 py-2 font-mono text-xs">{item.productId}</td>
                    <td className="px-3 py-2 font-mono text-xs text-gray-500">{item.sortOrder}</td>
                    <td className="px-3 py-2">
                      {item.pinned
                        ? <span className="text-xs px-2 py-0.5 rounded-full font-semibold bg-blue-100 text-blue-800">고정</span>
                        : <span className="text-xs text-gray-400">일반</span>}
                    </td>
                    <td className="px-3 py-2 text-right">
                      <button
                        onClick={() => void removeItem(item.productId)}
                        className="px-2 py-1 rounded border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-50"
                      >
                        빼기
                      </button>
                    </td>
                  </tr>
                ))}
                {!itemsLoading && items.length === 0 && (
                  <tr>
                    <td colSpan={4} className="px-3 py-8 text-center text-gray-400">
                      아직 담긴 상품이 없습니다.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>

          <p className="text-[11px] text-gray-400 mt-3">
            고정한 상품이 표시 순서와 무관하게 맨 앞에 옵니다. 정렬은 서버가 확정해 내려 주므로 이 목록의
            순서가 실제 진열 순서입니다.
          </p>
        </section>
      )}
    </div>
  );
};

export default DisplaySectionAdminPage;
