import React, { useState, useCallback } from 'react';

type Player = 'black' | 'white' | null;
type Board = Player[][];

const BOARD_SIZE = 19;

interface CapturedStones {
  black: number;
  white: number;
}

const BadukGame: React.FC = () => {
  const [board, setBoard] = useState<Board>(() =>
    Array(BOARD_SIZE).fill(null).map(() => Array(BOARD_SIZE).fill(null))
  );
  const [currentPlayer, setCurrentPlayer] = useState<'black' | 'white'>('black');
  const [capturedStones, setCapturedStones] = useState<CapturedStones>({ black: 0, white: 0 });
  const [lastMove, setLastMove] = useState<[number, number] | null>(null);
  const [passCount, setPassCount] = useState(0);
  const [gameEnded, setGameEnded] = useState(false);

  // 연결된 돌 그룹 찾기 (DFS)
  const getConnectedStones = useCallback((board: Board, row: number, col: number, player: Player, visited: boolean[][]): [number, number][] => {
    if (
      row < 0 || row >= BOARD_SIZE ||
      col < 0 || col >= BOARD_SIZE ||
      visited[row][col] ||
      board[row][col] !== player
    ) {
      return [];
    }

    visited[row][col] = true;
    let group: [number, number][] = [[row, col]];

    const directions = [[0, 1], [1, 0], [0, -1], [-1, 0]];
    for (const [dx, dy] of directions) {
      group = group.concat(getConnectedStones(board, row + dx, col + dy, player, visited));
    }

    return group;
  }, []);

  // 그룹의 활로(liberty) 개수 계산
  const countLiberties = useCallback((board: Board, group: [number, number][]): number => {
    const liberties = new Set<string>();

    for (const [row, col] of group) {
      const directions = [[0, 1], [1, 0], [0, -1], [-1, 0]];
      for (const [dx, dy] of directions) {
        const newRow = row + dx;
        const newCol = col + dy;
        if (
          newRow >= 0 && newRow < BOARD_SIZE &&
          newCol >= 0 && newCol < BOARD_SIZE &&
          board[newRow][newCol] === null
        ) {
          liberties.add(`${newRow},${newCol}`);
        }
      }
    }

    return liberties.size;
  }, []);

  // 잡힌 돌 제거
  const removeCapturedStones = useCallback((board: Board, opponent: Player): [Board, number] => {
    const newBoard = board.map(r => [...r]);
    const visited = Array(BOARD_SIZE).fill(null).map(() => Array(BOARD_SIZE).fill(false));
    let capturedCount = 0;

    for (let row = 0; row < BOARD_SIZE; row++) {
      for (let col = 0; col < BOARD_SIZE; col++) {
        if (newBoard[row][col] === opponent && !visited[row][col]) {
          const group = getConnectedStones(newBoard, row, col, opponent, visited);
          const liberties = countLiberties(newBoard, group);

          if (liberties === 0) {
            // 활로가 없으면 돌을 제거
            for (const [r, c] of group) {
              newBoard[r][c] = null;
              capturedCount++;
            }
          }
        }
      }
    }

    return [newBoard, capturedCount];
  }, [getConnectedStones, countLiberties]);

  // 착수 가능 여부 확인 (자살수 체크)
  const isValidMove = useCallback((board: Board, row: number, col: number, player: Player): boolean => {
    if (board[row][col] !== null) return false;

    // 임시로 돌을 놓아봄
    const tempBoard = board.map(r => [...r]);
    tempBoard[row][col] = player;

    // 상대 돌을 잡을 수 있는지 확인
    const opponent: Player = player === 'black' ? 'white' : 'black';
    const [, capturedCount] = removeCapturedStones(tempBoard, opponent);

    if (capturedCount > 0) {
      return true; // 상대 돌을 잡을 수 있으면 유효
    }

    // 자신의 그룹이 활로가 있는지 확인
    const visited = Array(BOARD_SIZE).fill(null).map(() => Array(BOARD_SIZE).fill(false));
    const group = getConnectedStones(tempBoard, row, col, player, visited);
    const liberties = countLiberties(tempBoard, group);

    return liberties > 0; // 활로가 있으면 유효
  }, [removeCapturedStones, getConnectedStones, countLiberties]);

  const handleCellClick = useCallback((row: number, col: number) => {
    if (gameEnded || board[row][col] !== null) return;

    if (!isValidMove(board, row, col, currentPlayer)) {
      alert('그곳에는 둘 수 없습니다. (자살수)');
      return;
    }

    const newBoard = board.map(r => [...r]);
    newBoard[row][col] = currentPlayer;
    setLastMove([row, col]);

    // 상대 돌 제거
    const opponent: Player = currentPlayer === 'black' ? 'white' : 'black';
    const [finalBoard, capturedCount] = removeCapturedStones(newBoard, opponent);
    setBoard(finalBoard);

    if (capturedCount > 0) {
      setCapturedStones(prev => ({
        ...prev,
        [opponent]: prev[opponent] + capturedCount,
      }));
    }

    setCurrentPlayer(opponent);
    setPassCount(0); // 착수하면 패스 카운트 리셋
  }, [board, currentPlayer, gameEnded, isValidMove, removeCapturedStones]);

  const handlePass = () => {
    if (gameEnded) return;

    const newPassCount = passCount + 1;
    setPassCount(newPassCount);

    if (newPassCount >= 2) {
      setGameEnded(true);
      alert('양측 모두 패스했습니다. 게임이 종료되었습니다.');
    } else {
      setCurrentPlayer(currentPlayer === 'black' ? 'white' : 'black');
    }
  };

  const resetGame = () => {
    setBoard(Array(BOARD_SIZE).fill(null).map(() => Array(BOARD_SIZE).fill(null)));
    setCurrentPlayer('black');
    setCapturedStones({ black: 0, white: 0 });
    setLastMove(null);
    setPassCount(0);
    setGameEnded(false);
  };

  return (
    <div className="min-h-screen bg-gradient-to-br from-amber-50 to-yellow-100 py-8 px-4">
      <div className="max-w-6xl mx-auto">
        <div className="text-center mb-6">
          <h1 className="text-4xl font-bold text-gray-900 mb-2">🎯 바둑 게임</h1>
          <p className="text-gray-700">19×19 바둑판에서 즐기는 전통 바둑</p>
        </div>

        {/* 게임 정보 */}
        <div className="bg-white rounded-lg shadow-lg p-6 mb-6">
          <div className="flex flex-col md:flex-row justify-between items-center gap-4">
            <div className="flex items-center gap-3">
              <span className="text-lg font-semibold text-gray-700">현재 차례:</span>
              <div className="flex items-center gap-2">
                {currentPlayer === 'black' ? (
                  <>
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-gray-800 to-black shadow-lg"></div>
                    <span className="font-bold text-gray-900">흑돌</span>
                  </>
                ) : (
                  <>
                    <div className="w-8 h-8 rounded-full bg-gradient-to-br from-white to-gray-200 border-2 border-gray-300 shadow-lg"></div>
                    <span className="font-bold text-gray-900">백돌</span>
                  </>
                )}
              </div>
            </div>

            <div className="flex items-center gap-4">
              <div className="text-center">
                <p className="text-sm text-gray-600">흑돌이 잡은 돌</p>
                <p className="text-2xl font-bold text-gray-900">{capturedStones.white}</p>
              </div>
              <div className="text-center">
                <p className="text-sm text-gray-600">백돌이 잡은 돌</p>
                <p className="text-2xl font-bold text-gray-900">{capturedStones.black}</p>
              </div>
            </div>

            <div className="flex gap-2">
              <button
                onClick={handlePass}
                disabled={gameEnded}
                className="px-4 py-2 bg-yellow-600 text-white rounded-lg font-semibold hover:bg-yellow-700 disabled:opacity-50 transition-colors"
              >
                패스
              </button>
              <button
                onClick={resetGame}
                className="px-4 py-2 bg-blue-600 text-white rounded-lg font-semibold hover:bg-blue-700 transition-colors"
              >
                새 게임
              </button>
            </div>
          </div>

          {gameEnded && (
            <div className="mt-4 p-4 bg-blue-100 border-2 border-blue-400 rounded-lg text-center">
              <p className="text-xl font-bold text-blue-800">
                게임이 종료되었습니다!
              </p>
              <p className="text-sm text-blue-700 mt-2">
                잡은 돌: 흑 {capturedStones.white}개, 백 {capturedStones.black}개
              </p>
            </div>
          )}
        </div>

        {/* 바둑판 */}
        <div className="bg-amber-700 rounded-lg shadow-2xl p-4 overflow-auto">
          <div className="bg-amber-600 rounded-lg p-3">
            <div
              className="inline-grid gap-0"
              style={{
                gridTemplateColumns: `repeat(${BOARD_SIZE}, minmax(0, 1fr))`,
              }}
            >
              {board.map((row, rowIndex) =>
                row.map((cell, colIndex) => {
                  const isLastMove = lastMove && lastMove[0] === rowIndex && lastMove[1] === colIndex;
                  const isStarPoint =
                    ((rowIndex === 3 || rowIndex === 9 || rowIndex === 15) &&
                     (colIndex === 3 || colIndex === 9 || colIndex === 15));

                  return (
                    <div
                      key={`${rowIndex}-${colIndex}`}
                      onClick={() => handleCellClick(rowIndex, colIndex)}
                      className={`
                        relative w-6 h-6 sm:w-7 sm:h-7 md:w-8 md:h-8
                        border-t border-l border-gray-800
                        ${rowIndex === BOARD_SIZE - 1 ? 'border-b' : ''}
                        ${colIndex === BOARD_SIZE - 1 ? 'border-r' : ''}
                        ${!cell && !gameEnded ? 'cursor-pointer hover:bg-amber-500/30' : ''}
                        transition-colors
                      `}
                    >
                      {cell && (
                        <div className="absolute inset-0 flex items-center justify-center">
                          <div
                            className={`
                              w-5 h-5 sm:w-6 sm:h-6 md:w-7 md:h-7 rounded-full
                              ${cell === 'black'
                                ? 'bg-gradient-to-br from-gray-800 to-black shadow-lg'
                                : 'bg-gradient-to-br from-white to-gray-200 border-2 border-gray-300 shadow-lg'
                              }
                              ${isLastMove ? 'ring-4 ring-red-500 ring-opacity-50' : ''}
                              transition-all duration-200 transform hover:scale-110
                            `}
                          />
                        </div>
                      )}

                      {/* 화점 표시 */}
                      {isStarPoint && (
                        <div className="absolute inset-0 flex items-center justify-center">
                          <div className="w-2 h-2 rounded-full bg-gray-900"></div>
                        </div>
                      )}
                    </div>
                  );
                })
              )}
            </div>
          </div>
        </div>

        {/* 게임 규칙 */}
        <div className="mt-6 bg-white rounded-lg shadow-lg p-6">
          <h3 className="text-xl font-bold text-gray-900 mb-3">📜 게임 규칙</h3>
          <ul className="space-y-2 text-gray-700">
            <li>• 흑돌이 먼저 시작합니다</li>
            <li>• 상대의 돌을 포위하면 잡을 수 있습니다</li>
            <li>• 자살수(활로가 없는 곳에 두기)는 금지됩니다</li>
            <li>• 양측 모두 패스하면 게임이 종료됩니다</li>
            <li>• 빨간 테두리는 가장 최근에 놓은 돌을 표시합니다</li>
            <li>• 검은 점은 화점(星)을 나타냅니다</li>
          </ul>
        </div>
      </div>
    </div>
  );
};

export default BadukGame;
