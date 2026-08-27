package github.lms.lemuel.wishlist.application.port.in;

import github.lms.lemuel.wishlist.domain.Wishlist;
import github.lms.lemuel.wishlist.domain.WishlistEntry;

import java.util.List;

/**
 * 찜 인바운드 포트 — REST 컨트롤러의 단일 진입점.
 *
 * <p><b>토글이 아니다.</b> 이식 대상이던 레거시는 담기와 빼기를 한 엔드포인트에 몰아넣고,
 * "읽어 보니 있길래 지웠다 / 없길래 넣었다"를 코드 {@code 0000}/{@code 1111} 로 돌려줬다.
 * 두 가지가 동시에 어긋난다.
 * <ul>
 *   <li>응답이 <i>결과 상태</i>가 아니라 <i>방금 한 동작</i>이라, 화면은 하트를 켤지 끌지
 *       추론해야 하고 그 추론은 요청이 겹치면 곧바로 틀린다.</li>
 *   <li>읽고 나서 쓰는 사이에 같은 요청이 또 오면(더블탭·재시도) 같은 상품이 두 줄 들어간다.
 *       레거시 테이블에는 (회원, 상품) 유니크 제약이 없어 실제로 중복이 쌓였고, 목록 개수가
 *       사용자가 담은 것보다 커졌다.</li>
 * </ul>
 *
 * <p>그래서 {@link #add}/{@link #remove} 를 나누고 <b>결과 상태</b>를 돌려준다. 두 번 호출해도
 * 결과가 같다(멱등). 중복 방지의 정본은 DB 의 {@code UNIQUE (user_id, product_id)} 이며,
 * 애플리케이션의 사전 검사는 최적화일 뿐 보증이 아니다.
 */
public interface WishlistUseCase {

    /** 목록 전체. 살 수 없는 상품도 사유와 함께 그대로 들어 있다. */
    Wishlist list(Long userId);

    /** 담는다. 이미 담겨 있으면 아무것도 하지 않는다. */
    Mutation add(Long userId, Long productId);

    /** 뺀다. 담겨 있지 않으면 아무것도 하지 않는다. */
    Mutation remove(Long userId, Long productId);

    /** 하트 표시용 단건 조회. 목록 전체를 읽지 않는다. */
    boolean contains(Long userId, Long productId);

    /**
     * 단종·삭제된 것만 일괄 정리한다. 품절은 남긴다.
     *
     * @return 실제로 지운 항목들. 화면이 "무엇을 지웠는지" 말할 수 있어야 하므로 개수가 아니라 목록이다
     */
    PurgeResult purgeGone(Long userId);

    /**
     * 담기/빼기의 결과.
     *
     * @param present 호출이 끝난 뒤 <b>담겨 있는가</b>. 방금 한 동작이 아니라 결과다
     * @param changed 이번 호출로 실제로 바뀌었는가. 화면 토스트를 띄울지 판단하는 데만 쓴다
     * @param size    호출이 끝난 뒤 총 개수. 헤더 뱃지를 다시 읽지 않아도 되게 함께 준다
     */
    record Mutation(boolean present, boolean changed, int size) {}

    /**
     * 일괄 정리 결과.
     *
     * @param removed   지워진 항목들(지우기 직전의 모습)
     * @param remaining 정리 후 남은 목록
     */
    record PurgeResult(List<WishlistEntry> removed, Wishlist remaining) {
        public PurgeResult {
            removed = List.copyOf(removed);
        }
    }
}
