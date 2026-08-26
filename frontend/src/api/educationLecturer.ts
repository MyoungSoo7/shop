import api from './axios';

export interface Lecturer {
  id: string;
  name: string;
  englishName?: string | null;
  graduateSchool?: string | null;
  officeName?: string | null;
  career?: string | null;
  lecturerType?: string | null;
  historyKo?: string | null;
  historyEn?: string | null;
  etcMemo?: string | null;
  majors: string[];
  lectureFields: string[];
  /** 지금 강의를 맡길 수 있는가. `deleted` 와 다른 축이다 — 쉬는 것과 명부에서 뺀 것은 다르다. */
  active: boolean;
  deleted: boolean;
  deletedAt?: string | null;
  updatedBy: string;
  version: number;
}

export interface LecturerPage {
  content: Lecturer[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export interface LecturerAssignment {
  id: string;
  courseId: string;
  courseTitle: string;
  lecturerId: string;
  lecturerName: string;
  assignedAt: string;
  assignedBy: string;
}

export interface LecturerSaveBody {
  name: string;
  englishName?: string;
  graduateSchool?: string;
  officeName?: string;
  career?: string;
  lecturerType?: string;
  historyKo?: string;
  historyEn?: string;
  etcMemo?: string;
  majors: string[];
  lectureFields: string[];
}

const base = '/admin/education/lecturers';

export const lecturerApi = {
  list: async (params: { keyword?: string; activeOnly?: boolean; page?: number; size?: number } = {}) =>
    (await api.get<LecturerPage>(base, { params })).data,
  get: async (id: string) => (await api.get<Lecturer>(`${base}/${id}`)).data,
  register: async (body: LecturerSaveBody) => (await api.post<Lecturer>(base, body)).data,
  update: async (id: string, body: LecturerSaveBody) => (await api.put<Lecturer>(`${base}/${id}`, body)).data,
  changeActivation: async (id: string, active: boolean) =>
    (await api.put<Lecturer>(`${base}/${id}/activation`, { active })).data,
  /** 명부에서 뺀다. 204 가 아니라 바뀐 강사가 돌아온다. 배정 이력은 남는다. */
  remove: async (id: string) => (await api.delete<Lecturer>(`${base}/${id}`)).data,
  assignments: async (id: string) => (await api.get<LecturerAssignment[]>(`${base}/${id}/courses`)).data,
  assign: async (id: string, courseId: string) =>
    (await api.post<LecturerAssignment>(`${base}/${id}/courses`, { courseId })).data,
  unassign: async (id: string, courseId: string) => {
    await api.delete(`${base}/${id}/courses/${courseId}`);
  },
  byCourse: async (courseId: string) =>
    (await api.get<LecturerAssignment[]>(`${base}/by-course/${courseId}`)).data,
};
