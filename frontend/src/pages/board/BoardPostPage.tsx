import React, { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  boardApi, boardAttachmentApi, boardCommentApi, boardPostApi,
  BoardAttachment, BoardComment, BoardDefinition, BoardPost,
} from '@/api/board';
import Spinner from '@/components/Spinner';
import { apiErrorMessage } from '@/lib/apiError';

/**
 * 게시글 상세 + 댓글.
 *
 * <p>버튼 노출은 서버가 내려준 `editable`·`deletable` 힌트로 정하지만, 그것은 어디까지나
 * 화면 편의다 — 실제 인가는 매 조작마다 서버(도메인)가 다시 판정한다.
 */
const BoardPostPage: React.FC = () => {
  const { boardKey = '', postId = '' } = useParams();
  const navigate = useNavigate();
  const id = Number(postId);

  const [definition, setDefinition] = useState<BoardDefinition | null>(null);
  const [post, setPost] = useState<BoardPost | null>(null);
  const [comments, setComments] = useState<BoardComment[]>([]);
  const [attachments, setAttachments] = useState<BoardAttachment[]>([]);
  const [uploading, setUploading] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState({ title: '', content: '', secret: false });
  const [commentText, setCommentText] = useState('');
  const [replyTo, setReplyTo] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [def, detail] = await Promise.all([boardApi.get(boardKey), boardPostApi.read(boardKey, id)]);
      setDefinition(def);
      setPost(detail);
      setForm({ title: detail.title, content: detail.content ?? '', secret: detail.secret });
      // 댓글이 꺼진 게시판이면 호출 자체를 하지 않는다 — 서버는 어차피 빈 목록을 주지만
      // 없는 기능을 위해 왕복을 늘릴 이유가 없다.
      setComments(def.content.commentsEnabled ? await boardCommentApi.list(boardKey, id) : []);
      // 첨부는 상세 응답이 이미 싣고 온다 — 왕복을 하나 줄이고, 게시판이 첨부를 꺼도
      // 이미 붙은 파일이 화면에 도달한다(정책은 미래를 향하므로 기존 파일은 남는다).
      setAttachments(detail.attachments ?? []);
      setError(null);
    } catch (e) {
      setError(apiErrorMessage(e, '글을 불러오지 못했습니다.'));
    } finally {
      setLoading(false);
    }
  }, [boardKey, id]);

  useEffect(() => {
    void load();
  }, [load]);

  const saveEdit = async () => {
    setBusy(true);
    try {
      await boardPostApi.update(boardKey, id, {
        title: form.title, content: form.content, secret: form.secret,
      });
      setEditing(false);
      await load();
    } catch (e) {
      setError(apiErrorMessage(e, '수정하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const removePost = async () => {
    if (!window.confirm('이 글을 삭제할까요?')) return;
    try {
      await boardPostApi.remove(boardKey, id);
      navigate(`/boards/${boardKey}`);
    } catch (e) {
      setError(apiErrorMessage(e, '삭제하지 못했습니다.'));
    }
  };

  const togglePin = async () => {
    if (!post) return;
    try {
      await boardPostApi.pin(boardKey, id, !post.pinned);
      await load();
    } catch (e) {
      setError(apiErrorMessage(e, '고정 상태를 바꾸지 못했습니다.'));
    }
  };

  const uploadAttachment = async (file: File) => {
    setUploading(true);
    try {
      await boardAttachmentApi.upload(boardKey, id, file);
      setAttachments(await boardAttachmentApi.list(boardKey, id));
      setError(null);
    } catch (e) {
      // 서버 메시지를 그대로 보여 준다 — "확장자와 다릅니다", "최대 5개까지" 처럼
      // 사용자가 고칠 수 있는 이유가 담겨 있다.
      setError(apiErrorMessage(e, '첨부를 올리지 못했습니다.'));
    } finally {
      setUploading(false);
    }
  };

  const removeAttachment = async (attachmentId: number) => {
    try {
      await boardAttachmentApi.remove(boardKey, attachmentId);
      setAttachments(await boardAttachmentApi.list(boardKey, id));
    } catch (e) {
      setError(apiErrorMessage(e, '첨부를 삭제하지 못했습니다.'));
    }
  };

  const submitComment = async () => {
    if (!commentText.trim()) return;
    setBusy(true);
    try {
      await boardCommentApi.create(boardKey, id, commentText, replyTo);
      setCommentText('');
      setReplyTo(null);
      setComments(await boardCommentApi.list(boardKey, id));
    } catch (e) {
      setError(apiErrorMessage(e, '댓글을 등록하지 못했습니다.'));
    } finally {
      setBusy(false);
    }
  };

  const removeComment = async (commentId: number) => {
    try {
      await boardCommentApi.remove(boardKey, commentId);
      setComments(await boardCommentApi.list(boardKey, id));
    } catch (e) {
      setError(apiErrorMessage(e, '댓글을 삭제하지 못했습니다.'));
    }
  };

  if (loading) {
    return <div className="py-20 flex justify-center"><Spinner size="lg" message="글 로드 중..." /></div>;
  }
  if (!post) {
    return (
      <div className="max-w-3xl mx-auto px-4 py-20 text-center space-y-4">
        <p className="text-gray-500">{error ?? '글을 찾을 수 없습니다.'}</p>
        <button onClick={() => navigate(`/boards/${boardKey}`)} className="text-blue-600 text-sm">
          목록으로
        </button>
      </div>
    );
  }

  return (
    <div className="max-w-3xl mx-auto px-4 py-8 space-y-5">
      <button onClick={() => navigate(`/boards/${boardKey}`)} className="text-sm text-gray-500 hover:text-gray-700">
        ← {definition?.name ?? '목록'}
      </button>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
      )}

      <article className="bg-white border border-gray-200 rounded-xl p-5 space-y-4">
        {editing ? (
          <div className="space-y-3">
            <input
              value={form.title}
              onChange={(e) => setForm({ ...form, title: e.target.value })}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg"
            />
            <textarea
              value={form.content}
              onChange={(e) => setForm({ ...form, content: e.target.value })}
              rows={12}
              className="w-full px-3 py-2 border border-gray-300 rounded-lg font-mono text-sm"
            />
            <div className="flex gap-2">
              <button
                onClick={saveEdit}
                disabled={busy}
                className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-semibold disabled:opacity-50"
              >
                저장
              </button>
              <button
                onClick={() => setEditing(false)}
                className="px-4 py-2 rounded-lg border border-gray-300 text-sm"
              >
                취소
              </button>
            </div>
          </div>
        ) : (
          <>
            <header className="space-y-2 border-b border-gray-100 pb-3">
              <div className="flex items-center gap-2">
                {post.pinned && (
                  <span className="text-xs px-2 py-0.5 rounded-full bg-rose-100 text-rose-700">공지</span>
                )}
                {post.secret && <span className="text-gray-400" title="비밀글">🔒</span>}
                <h1 className="text-xl font-bold text-gray-900">{post.title}</h1>
              </div>
              <div className="text-xs text-gray-500 flex gap-3">
                <span>{post.authorName}</span>
                <span>{new Date(post.createdAt).toLocaleString()}</span>
                <span>조회 {post.viewCount}</span>
              </div>
            </header>

            {/*
              HTML 게시판만 마크업으로 렌더한다. 이것이 안전한 근거는 **서버가 저장 시점에 정화**하기
              때문이다(JsoupHtmlSanitizerAdapter — 화이트리스트 방식이라 script·on* 이벤트·javascript:
              프로토콜이 DB 에 들어가지 못한다). 클라이언트 정화에 기대지 않는 이유는 간단하다:
              화면은 여러 개가 될 수 있고(모바일·이메일 다이제스트·PDF), 정화가 화면마다 복제되면
              반드시 한 곳이 빠진다. 경계는 쓰기 한 곳이다.

              그래서 이 분기를 다른 형식으로 넓히지 말 것 — TEXT·MARKDOWN 본문은 정화를 거치지 않으므로
              마크업으로 렌더하는 순간 저장된 원문이 그대로 실행된다.
            */}
            {post.contentFormat === 'HTML' ? (
              <div
                className="text-gray-800 leading-relaxed board-html"
                dangerouslySetInnerHTML={{ __html: post.content ?? '' }}
              />
            ) : (
              <div className="whitespace-pre-wrap text-gray-800 leading-relaxed">{post.content}</div>
            )}

            {/*
              첨부 섹션은 **파일이 있으면 언제나** 보여 준다 — 게시판이 첨부를 껐다고 이미 붙은
              파일을 감추면 데이터는 있는데 아무도 못 보는 상태가 되고, 직링크로는 여전히 받아져
              화면과 서버가 어긋난다(설계문서 §16). 정책이 막는 것은 '새로 올리는 것'뿐이다.
            */}
            {(attachments.length > 0 || (definition?.attachment.enabled && post.editable)) && (
              <section className="pt-3 border-t border-gray-100 space-y-3">
                {/* 이미지는 펼쳐 보여 준다 — 서버가 매직바이트로 IMAGE 라고 판정한 것만 kind 가 IMAGE 다 */}
                {attachments.filter((a) => a.kind === 'IMAGE').map((image) => (
                  <figure key={image.id} className="space-y-1">
                    <img src={image.downloadUrl} alt={image.originalName} loading="lazy"
                         className="max-w-full rounded-lg border border-gray-100" />
                    <figcaption className="text-xs text-gray-400 flex items-center gap-2">
                      {image.originalName}
                      {post.editable && (
                        <button onClick={() => removeAttachment(image.id)}
                                className="text-red-500 hover:text-red-700">삭제</button>
                      )}
                    </figcaption>
                  </figure>
                ))}

                {attachments.filter((a) => a.kind === 'FILE').length > 0 && (
                  <ul className="text-sm space-y-1">
                    {attachments.filter((a) => a.kind === 'FILE').map((file) => (
                      <li key={file.id} className="flex items-center gap-2">
                        <a href={file.downloadUrl} className="text-blue-600 hover:underline">
                          📎 {file.originalName}
                        </a>
                        <span className="text-xs text-gray-400">
                          {Math.ceil(file.sizeBytes / 1024)}KB
                        </span>
                        {post.editable && (
                          <button onClick={() => removeAttachment(file.id)}
                                  className="text-xs text-red-500 hover:text-red-700">삭제</button>
                        )}
                      </li>
                    ))}
                  </ul>
                )}

                {/* 추가 버튼만 정책을 따른다 — 껐으면 새로 올릴 수 없고, 기존 파일은 위에 그대로 있다 */}
                {definition?.attachment.enabled && post.editable && (
                  <label className="inline-flex items-center gap-2 text-sm text-gray-600 cursor-pointer">
                    <span className="px-3 py-1.5 rounded border border-gray-300 hover:bg-gray-50">
                      {uploading ? '올리는 중...' : '첨부 추가'}
                    </span>
                    <input
                      type="file"
                      className="hidden"
                      disabled={uploading}
                      onChange={(e) => {
                        const file = e.target.files?.[0];
                        // 같은 파일을 다시 고를 수 있도록 값을 비운다(onChange 가 안 걸리는 흔한 함정).
                        e.target.value = '';
                        if (file) void uploadAttachment(file);
                      }}
                    />
                    <span className="text-xs text-gray-400">
                      최대 {definition.attachment.maxCount}개 ·{' '}
                      {Math.floor(definition.attachment.maxSizeKb / 1024) || 1}MB ·{' '}
                      {definition.attachment.allowedExtensions.join(', ')}
                    </span>
                  </label>
                )}
              </section>
            )}

            {post.editable && (
              <div className="flex gap-2 pt-2 border-t border-gray-100 text-sm">
                <button onClick={() => setEditing(true)} className="px-3 py-1.5 rounded border border-gray-300">
                  수정
                </button>
                <button onClick={removePost} className="px-3 py-1.5 rounded text-red-600 hover:bg-red-50">
                  삭제
                </button>
                <button onClick={togglePin} className="px-3 py-1.5 rounded border border-gray-300">
                  {post.pinned ? '고정 해제' : '상단 고정'}
                </button>
              </div>
            )}
          </>
        )}
      </article>

      {definition?.content.commentsEnabled && (
        <section className="bg-white border border-gray-200 rounded-xl p-5 space-y-4">
          <h2 className="font-bold text-gray-900">댓글 {comments.length}</h2>

          <ul className="space-y-3">
            {comments.map((comment) => (
              <li
                key={comment.id}
                className={`text-sm ${comment.parentId ? 'pl-6 border-l-2 border-gray-100' : ''}`}
              >
                <div className="flex items-center gap-2 text-xs text-gray-500">
                  <span className="font-semibold text-gray-700">{comment.authorName}</span>
                  <span>{new Date(comment.createdAt).toLocaleString()}</span>
                  {comment.deletable && (
                    <button onClick={() => removeComment(comment.id)} className="text-red-500 hover:text-red-700">
                      삭제
                    </button>
                  )}
                  {!comment.parentId && comment.status === 'PUBLISHED' && (
                    <button onClick={() => setReplyTo(comment.id)} className="text-blue-500 hover:text-blue-700">
                      답글
                    </button>
                  )}
                </div>
                <p className={`mt-1 ${comment.status === 'DELETED' ? 'text-gray-400 italic' : 'text-gray-800'}`}>
                  {comment.content}
                </p>
              </li>
            ))}
            {comments.length === 0 && <li className="text-sm text-gray-400">첫 댓글을 남겨 보세요.</li>}
          </ul>

          <div className="space-y-2">
            {replyTo && (
              <div className="text-xs text-gray-500 flex items-center gap-2">
                답글 작성 중
                <button onClick={() => setReplyTo(null)} className="text-gray-400 hover:text-gray-600">취소</button>
              </div>
            )}
            <textarea
              value={commentText}
              onChange={(e) => setCommentText(e.target.value)}
              rows={3}
              placeholder="댓글을 입력하세요"
              className="w-full px-3 py-2 border border-gray-300 rounded-lg text-sm"
            />
            <button
              onClick={submitComment}
              disabled={busy}
              className="px-4 py-2 rounded-lg bg-blue-600 text-white text-sm font-semibold disabled:opacity-50"
            >
              등록
            </button>
          </div>
        </section>
      )}
    </div>
  );
};

export default BoardPostPage;
