import api from './axios';

export type CourseStatus = 'DRAFT' | 'PUBLISHED' | 'HIDDEN' | 'CLOSED';
export interface Course { id: string; title: string; description?: string | null; status: CourseStatus; updatedBy: string; version: number; }
export interface CoursePage { content: Course[]; totalElements: number; totalPages: number; number: number; size: number; }
export interface CourseRequest { title: string; description?: string; }
export interface Lesson { id: string; courseId: string; title: string; sequence: number; contentType: 'VIDEO' | 'DOCUMENT' | 'EXTERNAL_LINK'; contentRef: string; }

const base = '/admin/education/courses';
export const educationApi = {
  list: async (params: { status?: CourseStatus; query?: string; page?: number; size?: number } = {}) => (await api.get<CoursePage>(base, { params })).data,
  create: async (body: CourseRequest) => (await api.post<Course>(base, body)).data,
  update: async (id: string, body: CourseRequest) => (await api.put<Course>(`${base}/${id}`, body)).data,
  publish: async (id: string) => (await api.post<Course>(`${base}/${id}/publish`)).data,
  hide: async (id: string) => (await api.post<Course>(`${base}/${id}/hide`)).data,
  close: async (id: string) => (await api.post<Course>(`${base}/${id}/close`)).data,
  lessons: async (courseId: string) => (await api.get<Lesson[]>(`${base}/${courseId}/lessons`)).data,
  addLesson: async (courseId: string, body: { title: string; sequence: number; contentType: Lesson['contentType']; contentRef: string; required: boolean }) => (await api.post<Lesson>(`${base}/${courseId}/lessons`, body)).data,
  reorderLessons: async (courseId: string, lessonIds: string[]) => (await api.post<Lesson[]>(`${base}/${courseId}/lessons/reorder`, { lessonIds })).data,
};
