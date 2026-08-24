import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import BoardAdminPage from '@/pages/system/BoardAdminPage';
import { boardAdminApi, BoardDefinition, BOARD_SKIN_LABEL } from '@/api/board';
import { menuApi, MenuNode } from '@/api/system';

/**
 * 이 화면의 핵심은 "게시판 생성"과 "메뉴 등록"이 갈라져 있다는 것이다. 두 조작이 하나로 붙으면
 * 테스트로 만든 게시판이 즉시 전사 네비게이션에 뜬다. 그래서 생성이 menuApi 를 부르지 않는다는
 * 사실 자체를 테스트로 못박는다.
 *
 * 두 서비스를 잇는 대조(메뉴 없음 / 링크 끊긴 메뉴)도 조인이 아니라 이 화면이 하므로,
 * 배지와 배너가 실제 경로 집합 대조에서 나오는지까지 본다.
 *
 * 상수(BOARD_SKINS·라벨·skinRequires*)는 실물을 그대로 쓴다 — 테스트가 다시 적으면 상수가
 * 바뀌어도 초록이라 드리프트를 못 잡는다.
 */
vi.mock('@/api/board', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/board')>();
  return {
    ...actual,
    boardAdminApi: {
      list: vi.fn(), create: vi.fn(), update: vi.fn(),
      deactivate: vi.fn(), activate: vi.fn(), remove: vi.fn(),
    },
  };
});

vi.mock('@/api/system', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/system')>();
  return {
    ...actual,
    menuApi: { ...actual.menuApi, getTree: vi.fn(), create: vi.fn() },
  };
});

const mockedBoard = vi.mocked(boardAdminApi);
const mockedMenu = vi.mocked(menuApi);

const board = (over: Partial<BoardDefinition> & Pick<BoardDefinition, 'id' | 'boardKey' | 'name'>): BoardDefinition => ({
  description: null,
  skin: 'LIST',
  path: `/boards/${over.boardKey}`,
  content: { contentFormat: 'TEXT', commentsEnabled: true, secretEnabled: false, categoryGroupCode: null },
  attachment: { enabled: false, maxCount: 5, maxSizeKb: 5120, allowedExtensions: ['jpg', 'png'] },
  access: { readRoles: [], writeRoles: ['ADMIN'], commentRoles: ['ADMIN'], manageRoles: ['ADMIN'], publicRead: true },
  active: true,
  createdAt: '2026-08-15T00:00:00',
  updatedAt: '2026-08-15T00:00:00',
  ...over,
});

/** 공지 = 메뉴에 이미 올라간 공개 게시판 · 문의 = 메뉴 없이 닫혀 있는 QNA */
const 공지 = board({ id: 1, boardKey: 'notice', name: '공지사항', description: '전사 공지' });
const 문의 = board({
  id: 2, boardKey: 'qna', name: '문의', skin: 'QNA', active: false,
  content: { contentFormat: 'MARKDOWN', commentsEnabled: true, secretEnabled: true, categoryGroupCode: 'BOARD_CAT_QNA' },
  attachment: { enabled: true, maxCount: 3, maxSizeKb: 2048, allowedExtensions: ['pdf'] },
  access: { readRoles: ['USER'], writeRoles: ['USER'], commentRoles: ['ADMIN'], manageRoles: ['ADMIN'], publicRead: false },
});

const node = (over: Partial<MenuNode> & Pick<MenuNode, 'id' | 'name'>): MenuNode => ({
  parentId: null, shortName: null, path: null, icon: null, description: null,
  area: 'SHOP', menuType: 'ITEM', sortOrder: 0, requiredRole: null, requiredPermission: null,
  visible: true, active: true,
  createdAt: '2026-08-01T00:00:00', updatedAt: '2026-08-01T00:00:00', children: [],
  ...over,
});

/** SHOP > [공지 링크, 사라진 게시판 링크] · SYSTEM 그룹은 영역 필터 확인용 */
const 공지메뉴 = node({ id: 11, name: '공지사항', parentId: 10, path: '/boards/notice' });
const 유령메뉴 = node({ id: 12, name: '이벤트', parentId: 10, path: '/boards/legacy-event' });
const 고객센터 = node({ id: 10, name: '고객센터', menuType: 'GROUP', children: [공지메뉴, 유령메뉴] });
const 시스템 = node({ id: 20, name: '시스템', menuType: 'GROUP', area: 'SYSTEM' });

const boards = () => [공지, 문의];
const menuTree = () => [고객센터, 시스템];

// Field 가 label 을 input 에 묶지 않아(htmlFor 없음) 위치·라벨 텍스트로 집는다.
const boardKeyInput = () => screen.getByPlaceholderText('notice');
const textboxes = () => screen.getAllByRole('textbox');
const nameInput = () => textboxes()[1];
const descriptionInput = () => textboxes()[2];
/** '댓글' 은 체크박스 라벨과 역할 행 양쪽에 있다 — 입력 종류로 좁혀야 짚히는 곳이 하나가 된다. */
const labeled = (label: string, selector: string): HTMLInputElement =>
  screen.getAllByText(label)
    .map((el) => el.closest('label')?.querySelector<HTMLInputElement>(selector))
    .find((input): input is HTMLInputElement => !!input)!;
const roleInput = (label: string) => labeled(label, 'input.font-mono');
const checkbox = (label: string) => labeled(label, 'input[type="checkbox"]');
const skinSelect = () =>
  screen.getByRole('option', { name: BOARD_SKIN_LABEL.LIST }).closest('select')!;
const submit = () => screen.getByRole('button', { name: /게시판 만들기|수정 저장|저장 중/ });
const row = (name: string) => screen.getByText(name).closest('li')!;
const errorBanner = () => screen.queryByText(/불러오지 못했습니다|저장하지 못했습니다|삭제하지 못했습니다|바꾸지 못했습니다|추가하지 못했습니다/);

const renderPage = async () => {
  render(<BoardAdminPage />);
  await waitFor(() => expect(screen.getByText('게시판 관리')).toBeInTheDocument());
};

let confirmSpy: ReturnType<typeof vi.spyOn>;

beforeEach(() => {
  vi.clearAllMocks();
  mockedBoard.list.mockResolvedValue(boards());
  mockedBoard.create.mockResolvedValue(공지);
  mockedBoard.update.mockResolvedValue(공지);
  mockedBoard.deactivate.mockResolvedValue({ ...공지, active: false });
  mockedBoard.activate.mockResolvedValue(공지);
  mockedBoard.remove.mockResolvedValue(undefined);
  mockedMenu.getTree.mockResolvedValue(menuTree());
  mockedMenu.create.mockResolvedValue(공지메뉴);
  confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
});

afterEach(() => {
  confirmSpy.mockRestore();
});

describe('BoardAdminPage — 조회와 대조', () => {
  it('게시판과 메뉴를 함께 불러 목록을 세운다', async () => {
    await renderPage();

    expect(mockedBoard.list).toHaveBeenCalledTimes(1);
    expect(mockedMenu.getTree).toHaveBeenCalledTimes(1);
    expect(screen.getByText('2개')).toBeInTheDocument();
    expect(within(row('공지사항')).getByText('/boards/notice')).toBeInTheDocument();
  });

  it('메뉴 경로 집합과 대조해 연결 배지를 가른다 — cross-DB FK 대신 화면이 하는 대조다', async () => {
    await renderPage();

    expect(within(row('공지사항')).getByText('메뉴 연결됨')).toBeInTheDocument();
    expect(within(row('문의')).getByText('메뉴 없음')).toBeInTheDocument();
  });

  it('활성·공개 여부와 설명 공백을 그대로 드러낸다', async () => {
    await renderPage();

    expect(within(row('공지사항')).getByText('활성')).toBeInTheDocument();
    expect(within(row('공지사항')).getByText('공개')).toBeInTheDocument();
    expect(within(row('문의')).getByText('닫힘')).toBeInTheDocument();
    expect(within(row('문의')).getByText('설명 없음')).toBeInTheDocument();
  });

  it('활성인데 메뉴에 없는 게시판을 상단에 모아 경고한다 — 행별 배지만으론 긴 목록에서 놓친다', async () => {
    // 게시판(lemuel_board)과 메뉴(opslab)는 다른 DB라 FK 로 못 묶는다. 이 대조가 그 자리를 대신하므로
    // 화면이 안 보여 주면 어긋남을 아무도 모른다(G-5 — 분리의 대가로 수용한 지점).
    mockedBoard.list.mockResolvedValue([공지, board({ id: 3, boardKey: 'free', name: '자유게시판' })]);
    await renderPage();

    // '자유게시판'은 배너와 목록 행 양쪽에 나온다 — 배너 안에서 확인한다.
    const banner = screen.getByText(/메뉴에 없는 게시판/);
    expect(banner).toHaveTextContent('1개');
    expect(banner).toHaveTextContent('자유게시판');
  });

  it('닫힌 게시판은 메뉴가 없어도 세지 않는다 — 정상 절차라 경고가 소음이 된다', async () => {
    // 기본 픽스처: 공지(활성·연결됨) + 문의(닫힘·메뉴 없음)
    await renderPage();

    expect(screen.queryByText(/메뉴에 없는 게시판/)).not.toBeInTheDocument();
  });

  it('게시판이 없어진 /boards/ 메뉴는 링크 끊김으로 경고한다', async () => {
    await renderPage();

    expect(screen.getByText(/링크가 끊긴 메뉴 1건/)).toBeInTheDocument();
    expect(screen.getByText(/\/boards\/legacy-event/)).toBeInTheDocument();
  });

  it('게시판이 하나도 없으면 빈 안내를 보여 준다', async () => {
    mockedBoard.list.mockResolvedValue([]);
    await renderPage();

    expect(screen.getByText('아직 게시판이 없습니다.')).toBeInTheDocument();
  });

  it('메뉴 조회가 죽어도 게시판 관리는 계속된다 — 연결 배지만 못 그린다', async () => {
    mockedMenu.getTree.mockRejectedValue(new Error('menu down'));
    await renderPage();

    expect(screen.getAllByText('메뉴 없음')).toHaveLength(2);
    expect(screen.queryByText(/링크가 끊긴 메뉴/)).not.toBeInTheDocument();
  });

  it('게시판 조회 실패는 사유를 띄우고 닫을 수 있다', async () => {
    mockedBoard.list.mockRejectedValue({ response: { data: { message: '권한이 없습니다' } } });
    await renderPage();

    expect(screen.getByText('권한이 없습니다')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: '닫기' }));
    expect(screen.queryByText('권한이 없습니다')).not.toBeInTheDocument();
  });
});

describe('BoardAdminPage — 생성', () => {
  it('입력한 키·이름으로 만들고, 메뉴는 건드리지 않는다', async () => {
    await renderPage();
    fireEvent.change(boardKeyInput(), { target: { value: 'faq' } });
    fireEvent.change(nameInput(), { target: { value: '자주 묻는 질문' } });
    fireEvent.click(submit());

    await waitFor(() => expect(mockedBoard.create).toHaveBeenCalledTimes(1));
    expect(mockedBoard.create).toHaveBeenCalledWith(expect.objectContaining({
      boardKey: 'faq',
      name: '자주 묻는 질문',
      description: undefined,   // 빈 문자열을 그대로 보내면 설명이 '' 로 덮인다
      skin: 'LIST',
    }));
    expect(mockedMenu.create).not.toHaveBeenCalled();
  });

  it('역할 CSV 의 공백을 잘라 배열로 보내고, 읽기를 비우면 공개로 보낸다', async () => {
    await renderPage();
    fireEvent.change(boardKeyInput(), { target: { value: 'faq' } });
    fireEvent.change(roleInput('쓰기'), { target: { value: ' ADMIN , MANAGER , ' } });
    fireEvent.click(submit());

    await waitFor(() => expect(mockedBoard.create).toHaveBeenCalledTimes(1));
    expect(mockedBoard.create.mock.calls[0][0].access).toEqual({
      readRoles: [],                       // 비움 = 공개 게시판
      writeRoles: ['ADMIN', 'MANAGER'],
      commentRoles: ['ADMIN', 'MANAGER', 'USER'],
      manageRoles: ['ADMIN'],
    });
  });

  it('분류 코드그룹은 비면 null 로 보낸다 — 빈 문자열 코드그룹은 조회에서 안 걸린다', async () => {
    await renderPage();
    fireEvent.change(boardKeyInput(), { target: { value: 'faq' } });
    fireEvent.click(submit());

    await waitFor(() => expect(mockedBoard.create).toHaveBeenCalledTimes(1));
    expect(mockedBoard.create.mock.calls[0][0].content.categoryGroupCode).toBeNull();
  });

  it('만든 뒤 "아직 메뉴에 없다"고 알리고 목록을 다시 읽는다', async () => {
    mockedBoard.create.mockResolvedValue({ ...공지, name: 'FAQ' });
    await renderPage();
    fireEvent.change(boardKeyInput(), { target: { value: 'faq' } });
    fireEvent.click(submit());

    await waitFor(() => expect(screen.getByText(/아직 메뉴에는 올라가지 않았습니다/)).toBeInTheDocument());
    expect(screen.getByText(/게시판 'FAQ' 을 만들었습니다/)).toBeInTheDocument();
    expect(mockedBoard.list).toHaveBeenCalledTimes(2);
  });

  it('저장 실패는 사유를 띄우고 입력을 지우지 않는다', async () => {
    mockedBoard.create.mockRejectedValue({ response: { data: { message: '키가 중복입니다' } } });
    await renderPage();
    fireEvent.change(boardKeyInput(), { target: { value: 'notice' } });
    fireEvent.click(submit());

    await waitFor(() => expect(screen.getByText('키가 중복입니다')).toBeInTheDocument());
    expect(boardKeyInput()).toHaveValue('notice');
  });
});

describe('BoardAdminPage — 스킨이 전제하는 옵션', () => {
  it('GALLERY 를 고르면 첨부가 켜지고 끌 수 없다 (서버가 어차피 막는다)', async () => {
    await renderPage();
    fireEvent.change(skinSelect(), { target: { value: 'GALLERY' } });

    expect(checkbox('첨부')).toBeChecked();
    expect(checkbox('첨부')).toBeDisabled();
    expect(screen.getByDisplayValue('jpg,png,pdf')).toBeInTheDocument();  // 첨부 상세가 열린다
  });

  it('QNA 를 고르면 댓글이 켜지고 끌 수 없다', async () => {
    await renderPage();
    fireEvent.change(skinSelect(), { target: { value: 'QNA' } });

    expect(checkbox('댓글')).toBeChecked();
    expect(checkbox('댓글')).toBeDisabled();
  });

  it('전제가 없는 스킨에서는 첨부·비밀글을 직접 켜고 끌 수 있다', async () => {
    await renderPage();
    fireEvent.click(checkbox('첨부'));
    fireEvent.click(checkbox('비밀글'));
    fireEvent.change(screen.getByDisplayValue('5120'), { target: { value: '1024' } });
    fireEvent.change(screen.getByDisplayValue('5'), { target: { value: '9' } });
    fireEvent.change(screen.getByDisplayValue('jpg,png,pdf'), { target: { value: 'pdf' } });
    fireEvent.change(boardKeyInput(), { target: { value: 'gallery' } });
    fireEvent.click(submit());

    await waitFor(() => expect(mockedBoard.create).toHaveBeenCalledTimes(1));
    expect(mockedBoard.create.mock.calls[0][0].attachment).toEqual({
      enabled: true, maxCount: 9, maxSizeKb: 1024, allowedExtensions: ['pdf'],
    });
    expect(mockedBoard.create.mock.calls[0][0].content.secretEnabled).toBe(true);
  });
});

describe('BoardAdminPage — 수정', () => {
  it('수정을 누르면 정의를 폼으로 되살리고 키를 잠근다 — 키가 바뀌면 링크가 죽는다', async () => {
    await renderPage();
    fireEvent.click(within(row('문의')).getByRole('button', { name: '수정' }));

    expect(boardKeyInput()).toHaveValue('qna');
    expect(boardKeyInput()).toBeDisabled();
    expect(nameInput()).toHaveValue('문의');
    expect(roleInput('읽기')).toHaveValue('USER');
    expect(screen.getByDisplayValue('BOARD_CAT_QNA')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: '게시판 수정' })).toBeInTheDocument();
  });

  it('수정 저장은 update 로 가고 키는 본문에서 빠진다', async () => {
    await renderPage();
    fireEvent.click(within(row('공지사항')).getByRole('button', { name: '수정' }));
    fireEvent.change(nameInput(), { target: { value: '공지사항(개편)' } });
    fireEvent.change(descriptionInput(), { target: { value: '개편 안내' } });
    fireEvent.click(submit());

    await waitFor(() => expect(mockedBoard.update).toHaveBeenCalledTimes(1));
    expect(mockedBoard.update).toHaveBeenCalledWith(1, expect.objectContaining({
      name: '공지사항(개편)', description: '개편 안내',
    }));
    expect(mockedBoard.update.mock.calls[0][1]).not.toHaveProperty('boardKey');
    expect(mockedBoard.create).not.toHaveBeenCalled();
    await waitFor(() => expect(screen.getByText('수정했습니다.')).toBeInTheDocument());
  });

  it('"새로 만들기" 는 편집을 버리고 빈 폼으로 돌아간다', async () => {
    await renderPage();
    fireEvent.click(within(row('문의')).getByRole('button', { name: '수정' }));
    fireEvent.click(screen.getByRole('button', { name: '새로 만들기' }));

    expect(boardKeyInput()).toHaveValue('');
    expect(boardKeyInput()).toBeEnabled();
    expect(screen.getByRole('heading', { name: '새 게시판' })).toBeInTheDocument();
  });
});

describe('BoardAdminPage — 열기·닫기·삭제', () => {
  it('활성 게시판은 닫고, 닫힌 게시판은 연다', async () => {
    await renderPage();
    fireEvent.click(within(row('공지사항')).getByRole('button', { name: '닫기' }));
    await waitFor(() => expect(mockedBoard.deactivate).toHaveBeenCalledWith(1));

    fireEvent.click(within(row('문의')).getByRole('button', { name: '열기' }));
    await waitFor(() => expect(mockedBoard.activate).toHaveBeenCalledWith(2));
    expect(mockedBoard.list).toHaveBeenCalledTimes(3);
  });

  it('상태 변경 실패는 사유를 띄운다', async () => {
    mockedBoard.deactivate.mockRejectedValue(new Error('boom'));
    await renderPage();
    fireEvent.click(within(row('공지사항')).getByRole('button', { name: '닫기' }));

    await waitFor(() => expect(screen.getByText('상태를 바꾸지 못했습니다.')).toBeInTheDocument());
  });

  it('삭제는 확인을 받고, 취소하면 서버를 부르지 않는다', async () => {
    confirmSpy.mockReturnValue(false);
    await renderPage();
    fireEvent.click(within(row('문의')).getByRole('button', { name: '삭제' }));

    expect(confirmSpy).toHaveBeenCalledWith("'문의' 게시판을 삭제할까요? 되돌릴 수 없습니다.");
    await waitFor(() => expect(mockedBoard.remove).not.toHaveBeenCalled());
  });

  it('확인하면 삭제하고 목록을 다시 읽는다', async () => {
    await renderPage();
    fireEvent.click(within(row('문의')).getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(mockedBoard.remove).toHaveBeenCalledWith(2));
    expect(mockedBoard.list).toHaveBeenCalledTimes(2);
  });

  it('운영 중이라 거부되면 먼저 닫으라고 안내한다', async () => {
    mockedBoard.remove.mockRejectedValue(new Error('conflict'));
    await renderPage();
    fireEvent.click(within(row('공지사항')).getByRole('button', { name: '삭제' }));

    await waitFor(() => expect(screen.getByText(/운영 중인 게시판은 먼저 닫아야 합니다/)).toBeInTheDocument());
  });
});

describe('BoardAdminPage — 메뉴 연결 (두 번째 조작)', () => {
  const openDialog = async () => {
    await renderPage();
    fireEvent.click(within(row('문의')).getByRole('button', { name: '메뉴에 추가' }));
    return screen.getByRole('heading', { name: /메뉴에 추가 —/ }).closest('div')!;
  };

  it('이미 연결된 게시판에는 추가 버튼을 주지 않는다 — 중복 메뉴 행을 막는다', async () => {
    await renderPage();

    expect(within(row('공지사항')).queryByRole('button', { name: '메뉴에 추가' })).not.toBeInTheDocument();
    expect(within(row('문의')).getByRole('button', { name: '메뉴에 추가' })).toBeInTheDocument();
  });

  it('다이얼로그는 대상의 경로와 읽기 역할을 기본값으로 물고 열린다', async () => {
    const dialog = await openDialog();

    expect(within(dialog).getByText('/boards/qna')).toBeInTheDocument();
    expect(screen.getByDisplayValue('USER')).toBeInTheDocument();  // 노출 역할 = 읽기 역할
    expect(screen.getByDisplayValue('📋')).toBeInTheDocument();
  });

  it('상위 그룹 후보는 선택한 영역의 GROUP 만이고, 영역을 바꾸면 선택이 풀린다', async () => {
    const dialog = await openDialog();
    const parentSelect = within(dialog).getByRole('option', { name: '(최상위)' }).closest('select')!;
    expect([...parentSelect.querySelectorAll('option')].map((o) => o.textContent))
      .toEqual(['(최상위)', '고객센터']);   // 시스템 그룹은 SYSTEM 영역이라 빠진다

    fireEvent.change(parentSelect, { target: { value: '10' } });
    expect(parentSelect).toHaveValue('10');

    const areaSelect = within(dialog).getByRole('option', { name: 'SYSTEM' }).closest('select')!;
    fireEvent.change(areaSelect, { target: { value: 'SYSTEM' } });

    expect(parentSelect).toHaveValue('');   // 남의 영역 그룹 밑으로 들어가지 않게 초기화된다
    expect([...parentSelect.querySelectorAll('option')].map((o) => o.textContent))
      .toEqual(['(최상위)', '시스템']);
  });

  it('메뉴 행은 게시판이 아니라 order 의 menuApi 로 만든다', async () => {
    const dialog = await openDialog();
    const parentSelect = within(dialog).getByRole('option', { name: '(최상위)' }).closest('select')!;
    fireEvent.change(parentSelect, { target: { value: '10' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '메뉴에 추가' }));

    await waitFor(() => expect(mockedMenu.create).toHaveBeenCalledTimes(1));
    expect(mockedMenu.create).toHaveBeenCalledWith({
      name: '문의',
      path: '/boards/qna',
      icon: '📋',
      description: undefined,
      area: 'SHOP',
      menuType: 'ITEM',
      parentId: 10,
      sortOrder: 99,
      requiredRole: 'USER',
      visible: true,
    });
    await waitFor(() => expect(screen.getByText(/메뉴에 추가했습니다/)).toBeInTheDocument());
    expect(mockedBoard.list).toHaveBeenCalledTimes(2);
  });

  it('아이콘·노출 역할을 비우면 보내지 않는다 — 빈 문자열이 아이콘을 덮지 않게', async () => {
    const dialog = await openDialog();
    fireEvent.change(screen.getByDisplayValue('📋'), { target: { value: '' } });
    fireEvent.change(screen.getByDisplayValue('USER'), { target: { value: '' } });
    fireEvent.click(within(dialog).getByRole('button', { name: '메뉴에 추가' }));

    await waitFor(() => expect(mockedMenu.create).toHaveBeenCalledTimes(1));
    expect(mockedMenu.create).toHaveBeenCalledWith(expect.objectContaining({
      icon: undefined, requiredRole: undefined, parentId: null,
    }));
  });

  it('메뉴 등록 실패는 사유를 띄우고 다이얼로그를 닫지 않는다', async () => {
    mockedMenu.create.mockRejectedValue({ response: { data: { message: '경로가 중복입니다' } } });
    const dialog = await openDialog();
    fireEvent.click(within(dialog).getByRole('button', { name: '메뉴에 추가' }));

    await waitFor(() => expect(screen.getByText('경로가 중복입니다')).toBeInTheDocument());
    expect(screen.getByRole('heading', { name: /메뉴에 추가 —/ })).toBeInTheDocument();
  });

  it('취소하면 아무것도 만들지 않고 닫는다', async () => {
    const dialog = await openDialog();
    fireEvent.click(within(dialog).getByRole('button', { name: '취소' }));

    expect(screen.queryByRole('heading', { name: /메뉴에 추가 —/ })).not.toBeInTheDocument();
    expect(mockedMenu.create).not.toHaveBeenCalled();
    expect(errorBanner()).toBeNull();
  });
});
