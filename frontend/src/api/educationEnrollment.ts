import api from './axios';

export type EnrollmentStatus = 'WAITING' | 'CONFIRMED' | 'CANCELLED';

export interface Enrollment {
  id: string;
  courseId: string;
  applicantId: string;
  applicantName: string;
  applicantOrganization?: string | null;
  status: EnrollmentStatus;
  adminMemo?: string | null;
  cancelReason?: string | null;
  appliedAt: string;
  confirmedAt?: string | null;
  cancelledAt?: string | null;
  updatedBy: string;
  version: number;
}

export interface EnrollmentPage {
  content: Enrollment[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

/**
 * 정원 현황. `capacity`·`remaining` 이 `null` 이면 <b>정원 없음</b>이며 0 과 다르다 —
 * 0 은 마감이다. 화면은 이 둘을 절대 같은 칸에 같은 모양으로 그리면 안 된다.
 */
export interface CapacitySummary {
  courseId: string;
  courseTitle: string;
  capacity: number | null;
  remaining: number | null;
  confirmed: number;
  waiting: number;
  cancelled: number;
}

const base = '/admin/education/enrollments';

export const enrollmentApi = {
  list: async (params: { courseId?: string; status?: EnrollmentStatus; keyword?: string; page?: number; size?: number } = {}) =>
    (await api.get<EnrollmentPage>(base, { params })).data,
  summary: async (courseId: string) =>
    (await api.get<CapacitySummary>(`${base}/summary`, { params: { courseId } })).data,
  changeCapacity: async (courseId: string, capacity: number | null) =>
    (await api.put<CapacitySummary>(`${base}/capacity`, { capacity }, { params: { courseId } })).data,
  register: async (body: { courseId: string; applicantId: string; applicantName: string; applicantOrganization?: string }) =>
    (await api.post<Enrollment>(base, body)).data,
  confirm: async (id: string) => (await api.post<Enrollment>(`${base}/${id}/confirm`)).data,
  cancel: async (id: string, reason: string) => (await api.post<Enrollment>(`${base}/${id}/cancel`, { reason })).data,
  correct: async (id: string, body: { applicantName: string; applicantOrganization?: string }) =>
    (await api.put<Enrollment>(`${base}/${id}`, body)).data,
  memo: async (id: string, memo: string) => (await api.put<Enrollment>(`${base}/${id}/memo`, { memo })).data,
};
