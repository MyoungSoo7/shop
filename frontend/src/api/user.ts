import api from './axios';

/**
 * 로그인한 본인 정보 — GET /users/me
 *
 * 경로에 식별자가 없다는 점이 핵심이다: 서버가 JWT 주체에서 사용자를 정한다.
 * 프론트가 userId 를 알아내는 유일한 정상 경로이며, 로그인 응답(LoginResponse)에는
 * token·email·role 만 있어 id 가 없다.
 */
export interface MeResponse {
  id: number;
  email: string;
  role: string;
  name: string | null;
  phoneNumber: string | null;
  active: boolean;
  createdAt: string;
}

export const userApi = {
  /** GET /users/me — 인증 토큰의 주체 정보. 미인증이면 401. */
  me: async (): Promise<MeResponse> => {
    const response = await api.get<MeResponse>('/users/me');
    return response.data;
  },
};
