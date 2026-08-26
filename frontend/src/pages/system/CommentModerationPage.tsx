import { useCallback, useEffect, useState } from 'react';
import { boardAdminApi, type BoardDefinition } from '@/api/board';
import {
  COMMENT_STATUS_LABEL,
  REPORT_REASON_LABEL,
  REPORT_STATUS_LABEL,
  commentModerationApi,
  type CommentReport,
  type CommentReportStatus,
  type ModeratedComment,
  type ModeratedCommentStatus,
} from '@/api/commentModeration';

const dateText = (value?: string | null) =>
  value ? new Date(value).toLocaleString('ko-KR', { dateStyle: 'short', timeStyle: 'short' }) : '-';

/**
 * 댓글 통합 관리.
 *
 * <p>화면이 둘로 갈린 이유가 이 슬라이스의 전부다. 위쪽 <b>신고 큐</b>는 "누군가 문제 삼은 것"의
 * 목록이고, 아래쪽 <b>전체 댓글</b>은 "관리자가 직접 찾는" 목록이다. 원본(dentis)은 큐만 있었고
 * 큐에서 할 수 있는 일이 처리 여부 체크뿐이라, 정작 문제의 댓글은 손대지 못한 채 큐만 비었다.
 */
export default function CommentModerationPage() {
  const [boards, setBoards] = useState<BoardDefinition[]>([]);
  const [queueStatus, setQueueStatus] = useState<CommentReportStatus>('RECEIVED');
  const [queue, setQueue] = useState<CommentReport[] | null>(null);

  const [boardId, setBoardId] = useState('');
  const [status, setStatus] = useState<'' | ModeratedCommentStatus>('');
  const [reportedOnly, setReportedOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  // 입력칸과 실제로 조회에 쓰인 검색어를 나눠 둔다 — 한 글자마다 부르면 늦게 온 응답이 최신을 덮는다.
  const [query, setQuery] = useState('');
  const [rows, setRows] = useState<ModeratedComment[] | null>(null);

  const [detailOf, setDetailOf] = useState<number | null>(null);
  const [details, setDetails] = useState<CommentReport[]>([]);
  // 실패 자리를 셋으로 나눈다. 하나로 두면 두 조회가 서로의 실패를 지운다 — 큐가 죽었는데
  // 목록 조회가 성공하면 "신고 큐를 불러오지 못했습니다"가 조용히 사라진다.
  const [queueError, setQueueError] = useState<string | null>(null);
  const [listError, setListError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    void (async () => {
      try {
        setBoards(await boardAdminApi.list());
      } catch {
        setActionError('게시판 목록을 불러오지 못했습니다.');
      }
    })();
  }, []);

  const loadQueue = useCallback(async () => {
    try {
      setQueue((await commentModerationApi.queue({ status: queueStatus })).content);
      setQueueError(null);
    } catch {
      // 빈 표를 그리면 조회 실패가 "신고가 없다"로 위장한다.
      setQueue(null);
      setQueueError('신고 큐를 불러오지 못했습니다.');
    }
  }, [queueStatus]);

  const loadRows = useCallback(async () => {
    try {
      const page = await commentModerationApi.search({
        boardId: boardId ? Number(boardId) : undefined,
        status: status || undefined,
        keyword: query || undefined,
        reportedOnly: reportedOnly || undefined,
      });
      setRows(page.content);
      setListError(null);
    } catch {
      setRows(null);
      setListError('댓글을 불러오지 못했습니다.');
    }
  }, [boardId, status, query, reportedOnly]);

  useEffect(() => { void loadQueue(); }, [loadQueue]);
  useEffect(() => { void loadRows(); }, [loadRows]);

  const refresh = async () => { await loadQueue(); await loadRows(); };

  const hide = async (commentId: number) => {
    try {
      await commentModerationApi.hide(commentId);
      setActionError(null);
      await refresh();
    } catch {
      setActionError('가림에 실패했습니다. 이미 가려졌거나 삭제된 댓글일 수 있습니다.');
    }
  };

  const unhide = async (commentId: number) => {
    try {
      await commentModerationApi.unhide(commentId);
      setActionError(null);
      await refresh();
    } catch {
      setActionError('가림 해제에 실패했습니다.');
    }
  };

  const resolve = async (reportId: number, decision: 'HIDDEN' | 'KEPT') => {
    try {
      await commentModerationApi.resolve(reportId, decision);
      setActionError(null);
      await refresh();
    } catch {
      setActionError('판정에 실패했습니다. 이미 처리된 신고일 수 있습니다.');
    }
  };

  const openDetails = async (commentId: number) => {
    if (detailOf === commentId) {
      setDetailOf(null);
      setDetails([]);
      return;
    }
    try {
      setDetails(await commentModerationApi.reportsOf(commentId));
      setDetailOf(commentId);
      setActionError(null);
    } catch {
      setActionError('신고 내역을 불러오지 못했습니다.');
    }
  };

  return (
    <main className="space-y-6 p-6">
      <div>
        <h1 className="text-2xl font-bold">댓글 통합 관리</h1>
        <p className="text-sm text-gray-500">
          전 게시판의 댓글을 글을 거치지 않고 직접 조회하고, 신고를 판정합니다.
        </p>
      </div>

      {actionError && <div role="alert" className="text-red-600">{actionError}</div>}

      <section className="space-y-3">
        <div className="flex flex-wrap items-end gap-2">
          <h2 className="text-lg font-semibold">신고 큐</h2>
          <label className="flex flex-col text-sm">
            처리 상태
            <select
              aria-label="처리 상태"
              value={queueStatus}
              onChange={(e) => setQueueStatus(e.target.value as CommentReportStatus)}
              className="rounded border px-3 py-2"
            >
              <option value="RECEIVED">접수</option>
              <option value="HIDDEN">가림 처리</option>
              <option value="KEPT">유지 판정</option>
            </select>
          </label>
        </div>

        {queueError && <div role="alert" className="text-red-600">{queueError}</div>}

        {queue && (
          <table className="w-full text-sm">
            <thead>
              <tr>
                <th className="text-left">사유</th>
                <th className="text-left">설명</th>
                <th className="text-left">신고자</th>
                <th>접수일</th>
                <th className="text-left">처리</th>
                <th>판정</th>
              </tr>
            </thead>
            <tbody>
              {queue.map((report) => (
                <tr key={report.id} data-testid={`report-${report.id}`}>
                  <td>{REPORT_REASON_LABEL[report.reason]}</td>
                  <td>{report.detail ?? '-'}</td>
                  <td>{report.reporterName}</td>
                  <td>{dateText(report.createdAt)}</td>
                  <td data-testid={`report-status-${report.id}`}>
                    {REPORT_STATUS_LABEL[report.status]}
                    {report.handledBy ? ` (${report.handledBy})` : ''}
                  </td>
                  <td className="space-x-2">
                    {report.status === 'RECEIVED' && (
                      <>
                        {/* 판정과 조치가 한 번에 간다 — 원본은 이 둘이 갈라져 큐만 비었다. */}
                        <button onClick={() => void resolve(report.id, 'HIDDEN')}>가림 처리</button>
                        <button onClick={() => void resolve(report.id, 'KEPT')}>유지</button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {queue && queue.length === 0 && <p data-testid="queue-empty">해당 상태의 신고가 없습니다.</p>}
      </section>

      <section className="space-y-3">
        <h2 className="text-lg font-semibold">전체 댓글</h2>
        <div className="flex flex-wrap items-end gap-2">
          <label className="flex flex-col text-sm">
            게시판
            <select
              aria-label="게시판"
              value={boardId}
              onChange={(e) => setBoardId(e.target.value)}
              className="rounded border px-3 py-2"
            >
              <option value="">전체 게시판</option>
              {boards.map((board) => (
                <option key={board.id} value={board.id}>{board.name}</option>
              ))}
            </select>
          </label>
          <label className="flex flex-col text-sm">
            노출 상태
            <select
              aria-label="노출 상태"
              value={status}
              onChange={(e) => setStatus(e.target.value as '' | ModeratedCommentStatus)}
              className="rounded border px-3 py-2"
            >
              <option value="">전체 상태</option>
              <option value="PUBLISHED">노출</option>
              <option value="HIDDEN">가림</option>
              <option value="DELETED">삭제</option>
            </select>
          </label>
          <label className="flex flex-col text-sm">
            검색어
            <input
              aria-label="검색어"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="내용 또는 작성자"
              className="rounded border px-3 py-2"
            />
          </label>
          <label className="flex items-center gap-1 text-sm">
            <input
              type="checkbox"
              aria-label="신고된 댓글만"
              checked={reportedOnly}
              onChange={(e) => setReportedOnly(e.target.checked)}
            />
            신고된 댓글만
          </label>
          <button onClick={() => setQuery(keyword)} className="rounded bg-slate-700 px-4 py-2 text-white">조회</button>
        </div>

        {listError && <div role="alert" className="text-red-600">{listError}</div>}

        {rows && (
          <table className="w-full text-sm">
            <thead>
              <tr>
                <th className="text-left">게시판</th>
                <th className="text-left">글</th>
                <th className="text-left">작성자</th>
                <th className="text-left">내용</th>
                <th>상태</th>
                <th>신고</th>
                <th>작성일</th>
                <th>관리</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.id} data-testid={`comment-${row.id}`}>
                  <td>{row.boardName ?? '-'}</td>
                  {/* 글이 지워졌으면 제목이 없다. 빈 칸으로 두면 제목 없는 글과 구분되지 않는다. */}
                  <td>{row.postTitle ?? '(삭제된 글)'}</td>
                  <td>{row.authorName}</td>
                  <td>{row.content}</td>
                  <td data-testid={`comment-status-${row.id}`}>{COMMENT_STATUS_LABEL[row.status]}</td>
                  <td>
                    {row.reportCount > 0 ? (
                      <button data-testid={`reports-${row.id}`} onClick={() => void openDetails(row.id)}>
                        {row.reportCount}건
                      </button>
                    ) : (
                      '-'
                    )}
                  </td>
                  <td>{dateText(row.createdAt)}</td>
                  <td className="space-x-2">
                    {row.status === 'PUBLISHED' && <button onClick={() => void hide(row.id)}>가림</button>}
                    {row.status === 'HIDDEN' && <button onClick={() => void unhide(row.id)}>가림 해제</button>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {rows && rows.length === 0 && <p data-testid="empty">조건에 맞는 댓글이 없습니다.</p>}
      </section>

      {detailOf !== null && (
        <section data-testid="report-details" className="space-y-2 rounded border p-4">
          <h2 className="text-lg font-semibold">신고 내역</h2>
          <ul className="space-y-1 text-sm">
            {details.map((report) => (
              <li key={report.id}>
                {REPORT_REASON_LABEL[report.reason]} · {report.reporterName} · {dateText(report.createdAt)}
                {report.detail ? ` — ${report.detail}` : ''} · {REPORT_STATUS_LABEL[report.status]}
              </li>
            ))}
          </ul>
          <button onClick={() => { setDetailOf(null); setDetails([]); }} className="rounded border px-4 py-2">닫기</button>
        </section>
      )}
    </main>
  );
}
