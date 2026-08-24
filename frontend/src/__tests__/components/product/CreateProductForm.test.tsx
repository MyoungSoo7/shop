import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { flushSync } from 'react-dom';
import userEvent from '@testing-library/user-event';
import CreateProductForm from '@/components/product/CreateProductForm';
import { productApi } from '@/api/product';
import { ToastProvider } from '@/contexts/ToastContext';

vi.mock('@/api/product', () => ({
  productApi: {
    createProduct: vi.fn(),
  },
}));

const mockProduct = {
  id: 1,
  name: '신규 상품',
  description: '설명',
  price: 10000,
  stockQuantity: 100,
  status: 'ACTIVE' as const,
  availableForSale: true,
  createdAt: '2024-01-01T00:00:00',
  updatedAt: '2024-01-01T00:00:00',
};

/**
 * number 입력 필드 값 설정
 * - flushSync: fireEvent 후 React state를 동기적으로 즉시 커밋
 */
const setNumberInput = (labelRegex: RegExp, name: string, value: string) => {
  flushSync(() => {
    fireEvent.change(screen.getByLabelText(labelRegex), {
      target: { name, value },
    });
  });
};

const renderForm = (ui: React.ReactElement) => render(
  <ToastProvider>
    {ui}
  </ToastProvider>
);

describe('CreateProductForm', () => {
  beforeEach(() => {
    vi.resetAllMocks(); // mockOnce 큐까지 초기화
    vi.stubGlobal('alert', vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  // ─── 렌더링 ─────────────────────────────────────────────────
  describe('렌더링', () => {
    it('폼 요소들이 올바르게 렌더링된다', () => {
      renderForm(<CreateProductForm />);

      expect(screen.getByLabelText(/상품명/)).toBeInTheDocument();
      expect(screen.getByLabelText(/상품 설명/)).toBeInTheDocument();
      expect(screen.getByLabelText(/가격/)).toBeInTheDocument();
      expect(screen.getByLabelText(/초기 재고 수량/)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '상품 등록' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: '초기화' })).toBeInTheDocument();
    });

    it('onCancel prop이 있으면 취소 버튼이 렌더링된다', () => {
      renderForm(<CreateProductForm onCancel={vi.fn()} />);

      expect(screen.getByRole('button', { name: '취소' })).toBeInTheDocument();
    });

    it('onCancel prop이 없으면 취소 버튼이 렌더링되지 않는다', () => {
      renderForm(<CreateProductForm />);

      expect(screen.queryByRole('button', { name: '취소' })).not.toBeInTheDocument();
    });

    it('초기값이 빈 상태로 렌더링된다', () => {
      renderForm(<CreateProductForm />);

      expect(screen.getByLabelText(/상품명/)).toHaveValue('');
      expect(screen.getByLabelText(/상품 설명/)).toHaveValue('');
    });
  });

  // ─── 유효성 검사 ─────────────────────────────────────────────
  describe('유효성 검사', () => {
    it('상품명이 비어있으면 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      renderForm(<CreateProductForm />);

      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      expect(screen.getByText('상품명은 필수입니다.')).toBeInTheDocument();
    });

    it('상품명이 공백만 있으면 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      renderForm(<CreateProductForm />);

      await user.type(screen.getByLabelText(/상품명/), '   ');
      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      expect(screen.getByText('상품명은 필수입니다.')).toBeInTheDocument();
    });

    it('상품명이 200자를 초과하면 에러 메시지를 표시한다', async () => {
      const user = userEvent.setup();
      renderForm(<CreateProductForm />);

      // JSDOM이 maxlength를 강제하므로 속성 제거 후 값 설정
      const input = screen.getByLabelText(/상품명/) as HTMLInputElement;
      act(() => {
        input.removeAttribute('maxlength');
        fireEvent.change(input, {
          target: { name: 'name', value: 'a'.repeat(201) },
        });
      });
      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      expect(screen.getByText('상품명은 200자를 초과할 수 없습니다.')).toBeInTheDocument();
    });

    it('가격이 음수이면 에러 메시지를 표시한다', async () => {
      renderForm(<CreateProductForm />);

      // name 설정
      flushSync(() => {
        fireEvent.change(screen.getByLabelText(/상품명/), {
          target: { name: 'name', value: '상품' },
        });
      });
      // price를 음수로 설정 후 동기 커밋
      setNumberInput(/가격/, 'price', '-1');

      // form의 submit 이벤트를 직접 발생 (JSDOM에서 버튼 클릭으로 submit이 안 될 수 있음)
      act(() => {
        fireEvent.submit(
          screen.getByRole('button', { name: '상품 등록' }).closest('form')!
        );
      });

      await waitFor(() => {
        expect(screen.getByText('가격은 0 이상이어야 합니다.')).toBeInTheDocument();
      });
    });

    it('재고 수량이 음수이면 에러 메시지를 표시한다', async () => {
      renderForm(<CreateProductForm />);

      flushSync(() => {
        fireEvent.change(screen.getByLabelText(/상품명/), {
          target: { name: 'name', value: '상품' },
        });
      });
      setNumberInput(/초기 재고 수량/, 'stockQuantity', '-1');

      act(() => {
        fireEvent.submit(
          screen.getByRole('button', { name: '상품 등록' }).closest('form')!
        );
      });

      await waitFor(() => {
        expect(screen.getByText('재고 수량은 0 이상이어야 합니다.')).toBeInTheDocument();
      });
    });

    it('필드를 입력하면 해당 필드의 에러가 사라진다', async () => {
      const user = userEvent.setup();
      renderForm(<CreateProductForm />);

      await user.click(screen.getByRole('button', { name: '상품 등록' }));
      expect(screen.getByText('상품명은 필수입니다.')).toBeInTheDocument();

      await user.type(screen.getByLabelText(/상품명/), '상품명');
      expect(screen.queryByText('상품명은 필수입니다.')).not.toBeInTheDocument();
    });

    it('유효성 검사 실패 시 API를 호출하지 않는다', async () => {
      const user = userEvent.setup();
      renderForm(<CreateProductForm />);

      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      expect(productApi.createProduct).not.toHaveBeenCalled();
    });
  });

  // ─── 성공 제출 ───────────────────────────────────────────────
  describe('성공 제출', () => {
    it('유효한 폼 제출 시 API를 호출한다', async () => {
      const user = userEvent.setup();
      vi.mocked(productApi.createProduct).mockResolvedValueOnce(mockProduct);
      renderForm(<CreateProductForm />);

      await user.type(screen.getByLabelText(/상품명/), '신규 상품');
      await user.type(screen.getByLabelText(/상품 설명/), '상품 설명');
      setNumberInput(/가격/, 'price', '10000');
      setNumberInput(/초기 재고 수량/, 'stockQuantity', '100');
      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      await waitFor(() => {
        expect(productApi.createProduct).toHaveBeenCalledWith({
          name: '신규 상품',
          description: '상품 설명',
          price: 10000,
          stockQuantity: 100,
        });
      });
    });

    it('등록 성공 시 이미지 업로드 화면이 표시된다', async () => {
      const user = userEvent.setup();
      vi.mocked(productApi.createProduct).mockResolvedValueOnce(mockProduct);
      renderForm(<CreateProductForm />);

      await user.type(screen.getByLabelText(/상품명/), '신규 상품');
      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      await waitFor(() => {
        expect(screen.getByText(/신규 상품.*성공적으로 등록되었습니다/)).toBeInTheDocument();
      });
      expect(screen.getByRole('heading', { name: '상품 이미지 추가' })).toBeInTheDocument();
    });

    it('등록 성공 시 기존 입력 폼은 이미지 업로드 화면으로 대체된다', async () => {
      const user = userEvent.setup();
      vi.mocked(productApi.createProduct).mockResolvedValueOnce(mockProduct);
      renderForm(<CreateProductForm />);

      await user.type(screen.getByLabelText(/상품명/), '신규 상품');
      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      await waitFor(() => {
        expect(screen.queryByLabelText(/상품명/)).not.toBeInTheDocument();
      });
      expect(screen.getByRole('button', { name: '완료' })).toBeInTheDocument();
    });

    it('등록 성공 후 완료 버튼을 누르면 onSuccess 콜백이 호출된다', async () => {
      const user = userEvent.setup();
      vi.mocked(productApi.createProduct).mockResolvedValueOnce(mockProduct);
      const onSuccess = vi.fn();
      renderForm(<CreateProductForm onSuccess={onSuccess} />);

      await user.type(screen.getByLabelText(/상품명/), '신규 상품');
      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      await waitFor(() => {
        expect(screen.getByRole('button', { name: '완료' })).toBeInTheDocument();
      });
      expect(onSuccess).not.toHaveBeenCalled();

      await user.click(screen.getByRole('button', { name: '완료' }));
      expect(onSuccess).toHaveBeenCalledTimes(1);
    });
  });

  // ─── 실패 제출 ───────────────────────────────────────────────
  describe('실패 제출', () => {
    it('API 오류 시 에러 메시지가 표시된다', async () => {
      const user = userEvent.setup();
      vi.mocked(productApi.createProduct).mockRejectedValueOnce({
        response: { data: { message: '상품 등록에 실패했습니다.' } },
      });
      renderForm(<CreateProductForm />);

      await user.type(screen.getByLabelText(/상품명/), '신규 상품');
      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      await waitFor(() => {
        expect(screen.getByText('상품 등록에 실패했습니다.')).toBeInTheDocument();
      });
    });

    it('서버 메시지가 없으면 기본 에러 메시지가 표시된다', async () => {
      const user = userEvent.setup();
      vi.mocked(productApi.createProduct).mockRejectedValueOnce(new Error('Network Error'));
      renderForm(<CreateProductForm />);

      await user.type(screen.getByLabelText(/상품명/), '신규 상품');
      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      await waitFor(() => {
        expect(screen.getByText('상품 등록에 실패했습니다.')).toBeInTheDocument();
      });
    });

    it('제출 중에는 버튼이 비활성화된다', async () => {
      const user = userEvent.setup();
      let resolveSubmit!: (v: typeof mockProduct) => void;
      vi.mocked(productApi.createProduct).mockImplementationOnce(
        () => new Promise(resolve => { resolveSubmit = resolve; })
      );
      renderForm(<CreateProductForm />);

      await user.type(screen.getByLabelText(/상품명/), '신규 상품');
      await user.click(screen.getByRole('button', { name: '상품 등록' }));

      await waitFor(() => {
        expect(screen.getByRole('button', { name: '등록 중...' })).toBeDisabled();
      });

      // Promise 해제하여 정리
      await act(async () => { resolveSubmit(mockProduct); });
    });
  });

  // ─── 초기화 & 취소 ──────────────────────────────────────────
  describe('초기화 및 취소', () => {
    it('초기화 버튼을 클릭하면 폼이 리셋된다', async () => {
      const user = userEvent.setup();
      renderForm(<CreateProductForm />);

      await user.type(screen.getByLabelText(/상품명/), '입력한 값');
      await user.type(screen.getByLabelText(/상품 설명/), '설명 값');
      await user.click(screen.getByRole('button', { name: '초기화' }));

      expect(screen.getByLabelText(/상품명/)).toHaveValue('');
      expect(screen.getByLabelText(/상품 설명/)).toHaveValue('');
    });

    it('초기화 버튼을 클릭하면 에러 메시지가 사라진다', async () => {
      const user = userEvent.setup();
      renderForm(<CreateProductForm />);

      await user.click(screen.getByRole('button', { name: '상품 등록' }));
      expect(screen.getByText('상품명은 필수입니다.')).toBeInTheDocument();

      await user.click(screen.getByRole('button', { name: '초기화' }));
      expect(screen.queryByText('상품명은 필수입니다.')).not.toBeInTheDocument();
    });

    it('취소 버튼을 클릭하면 onCancel 콜백이 호출된다', async () => {
      const user = userEvent.setup();
      const onCancel = vi.fn();
      renderForm(<CreateProductForm onCancel={onCancel} />);

      await user.click(screen.getByRole('button', { name: '취소' }));

      expect(onCancel).toHaveBeenCalledTimes(1);
    });
  });
});
