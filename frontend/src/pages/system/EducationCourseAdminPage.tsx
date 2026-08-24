import { useEffect, useState } from 'react';
import { educationApi, type Course, type Lesson } from '@/api/education';

export default function EducationCourseAdminPage() {
  const [courses, setCourses] = useState<Course[]>([]);
  const [title, setTitle] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<Course | null>(null);
  const [lessons, setLessons] = useState<Lesson[]>([]);
  const [lessonTitle, setLessonTitle] = useState('');
  const load = async () => { try { setCourses((await educationApi.list()).content); } catch { setError('교육 과정을 불러오지 못했습니다.'); } };
  useEffect(() => { void load(); }, []);
  const create = async () => { if (!title.trim()) return; try { await educationApi.create({ title }); setTitle(''); await load(); } catch { setError('교육 과정 저장에 실패했습니다.'); } };
  const transition = async (course: Course, action: 'publish' | 'hide' | 'close') => { try { await educationApi[action](course.id); await load(); } catch { setError('상태 변경에 실패했습니다.'); } };
  const select = async (course: Course) => { try { setSelected(course); setLessons(await educationApi.lessons(course.id)); } catch { setError('차시를 불러오지 못했습니다.'); } };
  const addLesson = async () => { if (!selected || !lessonTitle.trim()) return; try { await educationApi.addLesson(selected.id, { title: lessonTitle, sequence: lessons.length + 1, contentType: 'VIDEO', contentRef: 'pending', required: true }); setLessons(await educationApi.lessons(selected.id)); setLessonTitle(''); } catch { setError('차시 저장에 실패했습니다.'); } };
  const moveUp = async (index: number) => { if (!selected || index === 0) return; const ids = lessons.map(x => x.id); [ids[index - 1], ids[index]] = [ids[index], ids[index - 1]]; try { setLessons(await educationApi.reorderLessons(selected.id, ids)); } catch { setError('차시 순서 변경에 실패했습니다.'); } };
  return <main className="p-6 space-y-6"><div><h1 className="text-2xl font-bold">교육 과정 관리</h1><p className="text-sm text-gray-500">과정과 게시 상태를 관리합니다.</p></div>
    {error && <div role="alert" className="text-red-600">{error}</div>}
    <div className="flex gap-2"><input aria-label="과정명" value={title} onChange={e => setTitle(e.target.value)} placeholder="새 과정명" className="border rounded px-3 py-2" /><button onClick={() => void create()} className="rounded bg-blue-600 px-4 py-2 text-white">과정 추가</button></div>
    <table className="w-full"><thead><tr><th className="text-left">과정명</th><th>상태</th><th>관리</th></tr></thead><tbody>{courses.map(course => <tr key={course.id}><td><button className="underline" onClick={() => void select(course)}>{course.title}</button></td><td>{course.status}</td><td className="space-x-2">{course.status === 'DRAFT' && <button onClick={() => void transition(course, 'publish')}>게시</button>}{course.status === 'PUBLISHED' && <button onClick={() => void transition(course, 'hide')}>숨김</button>}{course.status === 'HIDDEN' && <button onClick={() => void transition(course, 'close')}>종료</button>}</td></tr>)}</tbody></table>
    {selected && <section className="space-y-3"><h2 className="text-xl font-semibold">{selected.title} 차시</h2><div className="flex gap-2"><input aria-label="차시명" value={lessonTitle} onChange={e => setLessonTitle(e.target.value)} placeholder="차시명" className="border rounded px-3 py-2" /><button onClick={() => void addLesson()} className="rounded bg-slate-700 px-3 py-2 text-white">차시 추가</button></div><ol className="list-decimal pl-6">{lessons.map((lesson, index) => <li key={lesson.id} className="py-1">{lesson.title} <button disabled={index === 0} onClick={() => void moveUp(index)} className="ml-2 text-sm">위로</button></li>)}</ol></section>}
  </main>;
}
