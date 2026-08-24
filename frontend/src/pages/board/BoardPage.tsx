import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  boardApi, boardPostApi, BoardDefinition, BoardPost, BoardPageResponse,
} from '@/api/board';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 게시판 목록 — **단일 라우트가 모든 게시판을 그린다**.
 *
 * <p>`/boards/:boardKey` 하나가 정의를 읽어 스킨에 따라 렌더를 바꾼다. 게시판을 새로 만들어도
 * 라우트도 배포도 늘지 않는 이유가 이것이다.
 *
 * <p>Phase 2 는 LIST 스킨만 구현한다. GALLERY/FAQ/QNA 는 첨부(Phase 3) 이후에 붙이고,
 * 그때까지는 목록형으로 떨어뜨린다 — 렌더되지 않는 화면보다 낫다.
 */
const BoardPage: React.FC = () => {
  const { boardKey = '' } = useParams();
  const navigate = useNavigate();

  const [definition, setDefinition] = useState<BoardDefinition | null>(null);
  const [pageData, setPageData] = useState<BoardPageResponse<BoardPost> | null>(null);
  const [page, setPage] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  /** FAQ 아코디언 — 펼친 항목과, 펼칠 때 한 번 불러 기억해 둔 본문 */
  const [expandedId, setExpandedId] = useState<number | null>(null);
  const [bodies, setBodies] = useState<Record<number, string>>({});

  const [writing, setWriting] = useState(false);
  const [form, setForm] = useState({ title: '', content: '', secret: false });
  const [saving, setSaving] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [def, posts] = await Promise.all([
        boardApi.get(boardKey),
        boardPostApi.list(boardKey, { page, size: 20, keyword: search || undefined }),
      ]);
      setDefinition(def);
      setPageData(posts);
      setError(null);
    } catch (e) {
      setError(apiErrorMessage(e, '게시판을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [boardKey, page, search]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * FAQ 항목을 펼치거나 접는다.
   *
   * 이미 불러온 본문은 다시 부르지 않는다 — 접었다 폈다 할 때마다 왕복이 생기면 아코디언이
   * 목록보다 무거워진다. (상세 조회라 조회수는 첫 펼침에만 오른다.)
   */
  const toggleFaq = async (postId: number) => {
    if (expandedId === postId) {
      setExpandedId(null);
      return;
    }
    setExpandedId(postId);
    if (bodies[postId] !== undefined) return;
    try {
      const detail = await boardPostApi.read(boardKey, postId);
      setBodies((prev) => ({ ...prev, [postId]: detail.content ?? '' }));
    } catch (e) {
      setError(apiErrorMessage(e, '내용을 불러오지 못했습니다.'));
      setExpandedId(null);
    }
  };

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    setSaving(true);
    try {
      await boardPostApi.create(boardKey, {
        title: form.title,
        content: form.content,
        secret: form.secret,
      });
      setForm({ title: '', content: '', secret: false });
      setWriting(false);
      setPage(0);
      await load();
    } catch (e) {
      setError(apiErrorMessage(e, '글을 등록하지 못했습니다.'));
    } finally {
      setSaving(false);
    }
  };

  if (loading && !definition) {
    return <div className="py-20 flex justify-center"><Spinner size="lg" message="게시판 로드 중..." /></div>;
  }

  if (error && !definition) {
    return <p className="py-20 text-center text-gray-500">{error}</p>;
  }

  const posts = pageData?.content ?? [];

  return (
    <div className="max-w-5xl mx-auto px-4 py-8 space-y-5">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-gray-900">{definition?.name}</h1>
          {definition?.description && (
            <p className="text-sm text-gray-500 mt-1">{definition.description}</p>
          )}
        </div>
        <button
          onClick={() => setWriting((prev) => !prev)}
          className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-semibold hover:bg-blue-700"
        >
          {writing ? '닫기' : '글쓰기'}
        </button>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {writing && (
        <form onSubmit={submit} className="bg-white border border-gray-200 rounded-xl p-4 space-y-3">
          <input
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
            placeholder="제목"
            className="w-full px-3 py-2 border border-gray-300 rounded-lg"
          />
          <textarea
            value={form.content}
            onChange={(e) => setForm({ ...form, content: e.target.value })}
            placeholder="내용"
            rows={8}
            className="w-full px-3 py-2 border border-gray-300 rounded-lg font-mono text-sm"
          />
          <div className="flex items-center justify-between">
            {definition?.content.secretEnabled ? (
              <label className="flex items-center gap-2 text-sm text-gray-600">
                <input
                  type="checkbox"
                  checked={form.secret}
                  onChange={(e) => setForm({ ...form, secret: e.target.checked })}
                />
                비밀글
              </label>
            ) : <span />}
            <button
              type="submit"
              disabled={saving}
              className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-semibold hover:bg-blue-700 disabled:opacity-50"
            >
              {saving ? '등록 중...' : '등록'}
            </button>
          </div>
        </form>
      )}

      <div className="flex gap-2">
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter') { setPage(0); setSearch(keyword); } }}
          placeholder="제목 · 내용 검색"
          className="flex-1 px-3 py-2 border border-gray-300 rounded-lg text-sm"
        />
        <button
          onClick={() => { setPage(0); setSearch(keyword); }}
          className="px-4 py-2 rounded-lg border border-gray-300 text-sm hover:bg-gray-50"
        >
          검색
        </button>
      </div>

      {/*
        갤러리 스킨 — 대표 이미지는 목록 응답이 이미 담고 있다(thumbnailUrl). 글마다 첨부를 따로
        부르면 한 화면에 20번의 왕복이 생기므로, 서버가 페이지 전체를 한 번에 채워 내려준다.

        이미지가 없는 글도 목록에서 빠지지 않는다 — 자리표시를 그린다. "GALLERY 는 대표 이미지 필수"를
        글 생성 시점에 강제하지 않는 이유는 업로드가 글 생성 이후의 별도 요청이기 때문이다.
      */}
      {definition?.skin === 'GALLERY' ? (
        <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-4 gap-3">
          {posts.map((post) => (
            <button
              key={post.id}
              onClick={() => navigate(`/boards/${boardKey}/${post.id}`)}
              className="text-left bg-white border border-gray-200 rounded-xl overflow-hidden hover:shadow-md transition-shadow"
            >
              <div className="aspect-square bg-gray-100 flex items-center justify-center overflow-hidden">
                {post.thumbnailUrl ? (
                  <img src={post.thumbnailUrl} alt={post.title} loading="lazy"
                       className="w-full h-full object-cover" />
                ) : (
                  <span className="text-3xl text-gray-300">🖼️</span>
                )}
              </div>
              <div className="p-2">
                <p className="text-sm font-medium text-gray-900 truncate">{post.title}</p>
                <p className="text-xs text-gray-500 mt-0.5">{post.authorName} · 조회 {post.viewCount}</p>
              </div>
            </button>
          ))}
          {posts.length === 0 && (
            <p className="col-span-full py-12 text-center text-gray-400 text-sm">
              {search ? '검색 결과가 없습니다.' : '아직 글이 없습니다.'}
            </p>
          )}
        </div>
      ) : definition?.skin === 'FAQ' ? (
        /*
          FAQ 스킨 — 제목을 눌러 그 자리에서 펼친다.

          본문은 목록 응답에 없다(의도적으로 싣지 않는다). 펼칠 때 상세를 한 번 부르고 기억해 둔다 —
          미리 다 받아 오면 안 펼칠 항목의 본문까지 내려받게 되고, 그건 목록에서 본문을 뺀 이유를
          그대로 무너뜨린다.
        */
        <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
          <ul className="divide-y divide-gray-100">
            {posts.map((post) => (
              <li key={post.id}>
                <button
                  onClick={() => void toggleFaq(post.id)}
                  className="w-full text-left px-4 py-3 hover:bg-gray-50 flex items-center gap-2"
                  aria-expanded={expandedId === post.id}
                >
                  <span className="text-gray-400">{expandedId === post.id ? '▾' : '▸'}</span>
                  <span className="font-medium text-gray-900">{post.title}</span>
                </button>
                {expandedId === post.id && (
                  <div className="px-4 pb-4 pl-10 text-sm text-gray-700 whitespace-pre-wrap">
                    {bodies[post.id] ?? '불러오는 중...'}
                  </div>
                )}
              </li>
            ))}
            {posts.length === 0 && (
              <li className="px-4 py-12 text-center text-gray-400 text-sm">
                {search ? '검색 결과가 없습니다.' : '아직 등록된 항목이 없습니다.'}
              </li>
            )}
          </ul>
        </div>
      ) : (
      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden">
        <ul className="divide-y divide-gray-100">
          {posts.map((post) => (
            <li
              key={post.id}
              onClick={() => navigate(`/boards/${boardKey}/${post.id}`)}
              className="px-4 py-3 cursor-pointer hover:bg-gray-50"
            >
              <div className="flex items-center gap-2">
                {/* QNA 는 "답이 달렸는가"가 목록에서 가장 중요한 정보다 — 제목보다 먼저 온다 */}
                {definition?.skin === 'QNA' && (
                  <span className={`text-xs px-2 py-0.5 rounded-full ${
                    (post.commentCount ?? 0) > 0
                      ? 'bg-emerald-100 text-emerald-700'
                      : 'bg-amber-100 text-amber-700'
                  }`}>
                    {(post.commentCount ?? 0) > 0 ? '답변완료' : '답변대기'}
                  </span>
                )}
                {post.pinned && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-rose-100 text-rose-700">공지</span>
                )}
                {post.secret && <span className="text-gray-400" title="비밀글">🔒</span>}
                {post.status === 'HIDDEN' && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-gray-200 text-gray-600">숨김</span>
                )}
                <span className="font-medium text-gray-900 truncate">{post.title}</span>
              </div>
              <div className="mt-1 text-xs text-gray-500 flex gap-3">
                <span>{post.authorName}</span>
                <span>{new Date(post.createdAt).toLocaleDateString()}</span>
                <span>조회 {post.viewCount}</span>
              </div>
            </li>
          ))}
          {posts.length === 0 && (
            <li className="px-4 py-12 text-center text-gray-400 text-sm">
              {search ? '검색 결과가 없습니다.' : '아직 글이 없습니다.'}
            </li>
          )}
        </ul>
      </div>
      )}

      {pageData && pageData.totalPages > 1 && (
        <div className="flex items-center justify-center gap-2 text-sm">
          <button
            disabled={page === 0}
            onClick={() => setPage((p) => p - 1)}
            className="px-3 py-1.5 rounded border border-gray-300 disabled:opacity-40"
          >
            이전
          </button>
          <span className="text-gray-500">{page + 1} / {pageData.totalPages}</span>
          <button
            disabled={page + 1 >= pageData.totalPages}
            onClick={() => setPage((p) => p + 1)}
            className="px-3 py-1.5 rounded border border-gray-300 disabled:opacity-40"
          >
            다음
          </button>
        </div>
      )}
    </div>
  );
};

export default BoardPage;
