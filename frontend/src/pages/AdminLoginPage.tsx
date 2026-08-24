import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { authApi } from '@/api/auth';
import { RegisterRequest } from '@/types';
import { apiErrorMessage, apiErrorStatus, errorDetail } from '@/lib/apiError';

type Tab = 'login' | 'register';

const AdminLoginPage: React.FC = () => {
  const navigate = useNavigate();
  const [tab, setTab] = useState<Tab>('login');

  // 로그인 상태
  const [email, setEmail]       = useState('');
  const [password, setPassword] = useState('');

  // 회원가입 상태
  const [regForm, setRegForm] = useState<RegisterRequest>({ email: '', password: '', role: 'MANAGER' });
  const [confirmPassword, setConfirmPassword] = useState('');

  const [loading, setLoading] = useState(false);
  const [error, setError]     = useState<string | null>(null);
  const [success, setSuccess] = useState<string | null>(null);

  // ── 로그인 ──────────────────────────────────────────────
  const doLogin = async (creds: { email: string; password: string }) => {
    setLoading(true);
    setError(null);
    try {
      const response = await authApi.login(creds);
      if (response.role !== 'ADMIN' && response.role !== 'MANAGER') {
        setError('관리자(ADMIN) 또는 매니저(MANAGER) 계정으로만 로그인할 수 있습니다.');
        return;
      }
      authApi.saveToken(response);
      navigate('/admin');
    } catch (err) {
      setError(apiErrorMessage(err, '이메일 또는 비밀번호가 올바르지 않습니다.'));
    } finally {
      setLoading(false);
    }
  };

  const handleLoginSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    doLogin({ email, password });
  };

  // ── 회원가입 ─────────────────────────────────────────────
  const handleRegisterSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (regForm.password !== confirmPassword) {
      setError('비밀번호가 일치하지 않습니다.');
      return;
    }
    setLoading(true);
    setError(null);
    setSuccess(null);
    try {
      await authApi.register(regForm);
      setSuccess(`${regForm.role} 계정이 생성되었습니다. 로그인해주세요.`);
      setRegForm({ email: '', password: '', role: 'MANAGER' });
      setConfirmPassword('');
      setTab('login');
    } catch (err) {
      if (apiErrorStatus(err) === 409) {
        setError('이미 사용 중인 이메일입니다.');
      } else {
        setError(errorDetail(err, '회원가입에 실패했습니다.'));
      }
    } finally {
      setLoading(false);
    }
  };

  const switchTab = (t: Tab) => {
    setTab(t);
    setError(null);
    setSuccess(null);
  };

  return (
    <div className="min-h-screen bg-gray-900 flex flex-col items-center justify-center p-4">
      <div className="w-full max-w-md">

        {/* 로고 */}
        <div className="text-center mb-8">
          <h1 className="text-4xl font-bold text-white italic mb-1">Lemuel</h1>
          <p className="text-gray-400 text-sm">관리자 시스템</p>
        </div>

        {/* 카드 */}
        <div className="bg-white rounded-2xl shadow-2xl p-8">

          {/* 탭 */}
          <div className="flex rounded-xl overflow-hidden border border-gray-200 mb-6">
            <button
              type="button"
              onClick={() => switchTab('login')}
              className={`flex-1 py-2.5 text-sm font-semibold transition-colors ${
                tab === 'login' ? 'bg-gray-900 text-white' : 'text-gray-500 hover:bg-gray-50'
              }`}
            >
              로그인
            </button>
            <button
              type="button"
              onClick={() => switchTab('register')}
              className={`flex-1 py-2.5 text-sm font-semibold transition-colors ${
                tab === 'register' ? 'bg-gray-900 text-white' : 'text-gray-500 hover:bg-gray-50'
              }`}
            >
              회원가입
            </button>
          </div>

          {/* 알림 */}
          {error && (
            <div className="mb-5 bg-red-50 border border-red-200 rounded-lg p-3 text-sm text-red-700">
              {error}
            </div>
          )}
          {success && (
            <div className="mb-5 bg-green-50 border border-green-200 rounded-lg p-3 text-sm text-green-700">
              {success}
            </div>
          )}

          {/* ── 로그인 폼 ── */}
          {tab === 'login' && (
            <>
              <form onSubmit={handleLoginSubmit} className="space-y-4">
                <div className="rounded-xl border border-gray-300 overflow-hidden">
                  <input
                    type="email"
                    required
                    autoComplete="email"
                    placeholder="이메일"
                    value={email}
                    onChange={(e) => setEmail(e.target.value)}
                    className="block w-full px-4 py-3 text-sm text-gray-900 placeholder-gray-400 border-b border-gray-300 focus:outline-none focus:ring-0"
                  />
                  <input
                    type="password"
                    required
                    autoComplete="current-password"
                    placeholder="비밀번호"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    className="block w-full px-4 py-3 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-0"
                  />
                </div>

                <button
                  type="submit"
                  disabled={loading}
                  className="w-full py-3 bg-gray-900 text-white text-sm font-semibold rounded-xl hover:bg-gray-800 transition-colors disabled:opacity-50"
                >
                  {loading ? '로그인 중...' : '로그인'}
                </button>
              </form>

            </>
          )}

          {/* ── 회원가입 폼 ── */}
          {tab === 'register' && (
            <form onSubmit={handleRegisterSubmit} className="space-y-4">
              {/* 역할 선택 */}
              <div>
                <p className="text-xs font-semibold text-gray-500 mb-2">역할 선택</p>
                <div className="flex gap-3">
                  {(['MANAGER', 'ADMIN'] as const).map((role) => (
                    <button
                      key={role}
                      type="button"
                      onClick={() => setRegForm({ ...regForm, role })}
                      className={`flex-1 py-2.5 text-sm font-semibold rounded-xl border-2 transition-colors ${
                        regForm.role === role
                          ? role === 'ADMIN'
                            ? 'border-purple-600 bg-purple-50 text-purple-700'
                            : 'border-green-600 bg-green-50 text-green-700'
                          : 'border-gray-200 text-gray-500 hover:border-gray-300'
                      }`}
                    >
                      {role}
                    </button>
                  ))}
                </div>
              </div>

              <div className="rounded-xl border border-gray-300 overflow-hidden">
                <input
                  type="email"
                  required
                  autoComplete="email"
                  placeholder="이메일"
                  value={regForm.email}
                  onChange={(e) => setRegForm({ ...regForm, email: e.target.value })}
                  className="block w-full px-4 py-3 text-sm text-gray-900 placeholder-gray-400 border-b border-gray-300 focus:outline-none focus:ring-0"
                />
                <input
                  type="password"
                  required
                  autoComplete="new-password"
                  placeholder="비밀번호"
                  value={regForm.password}
                  onChange={(e) => setRegForm({ ...regForm, password: e.target.value })}
                  className="block w-full px-4 py-3 text-sm text-gray-900 placeholder-gray-400 border-b border-gray-300 focus:outline-none focus:ring-0"
                />
                <input
                  type="password"
                  required
                  autoComplete="new-password"
                  placeholder="비밀번호 확인"
                  value={confirmPassword}
                  onChange={(e) => setConfirmPassword(e.target.value)}
                  className="block w-full px-4 py-3 text-sm text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-0"
                />
              </div>

              <button
                type="submit"
                disabled={loading}
                className="w-full py-3 bg-gray-900 text-white text-sm font-semibold rounded-xl hover:bg-gray-800 transition-colors disabled:opacity-50"
              >
                {loading ? '가입 중...' : `${regForm.role} 계정 생성`}
              </button>
            </form>
          )}
        </div>

        {/* 뒤로 가기 */}
        <div className="text-center mt-6">
          <button
            onClick={() => navigate('/')}
            className="text-gray-500 hover:text-gray-300 text-sm transition-colors"
          >
            ← 시작 화면으로
          </button>
        </div>
      </div>
    </div>
  );
};

export default AdminLoginPage;