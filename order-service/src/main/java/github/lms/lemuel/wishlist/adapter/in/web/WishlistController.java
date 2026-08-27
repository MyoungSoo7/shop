package github.lms.lemuel.wishlist.adapter.in.web;

import github.lms.lemuel.web.security.ResourceOwnership;
import github.lms.lemuel.wishlist.application.port.in.WishlistUseCase;
import github.lms.lemuel.wishlist.domain.Wishlist;
import github.lms.lemuel.wishlist.domain.WishlistEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 찜(위시리스트) — {@code /users/{userId}/wishlist}.
 *
 * <p><b>소유권은 경로가 아니라 토큰이 정한다.</b> 모든 메서드가 첫 줄에서
 * {@link ResourceOwnership#requireSelfOrAdmin(Long)} 를 호출한다. 이식 대상이던 레거시는 소유자
 * 대조를 SQL 문자열 보간({@code AND WIS.MEMBER_IDX = ${member_idx}})으로 하고 있었다 — 값이
 * 파라미터가 아니라 쿼리 문법의 일부로 들어가므로, 그 값이 요청에서 온 것이라면 소유권 조건
 * 자체를 요청자가 다시 쓸 수 있다. 여기서는 경로의 {@code userId} 를 <b>신뢰하지 않고</b>
 * JWT 주체와 대조하며, 저장소 접근은 전부 바인딩 파라미터다.
 *
 * <p>SecurityConfig 에 이 경로의 별도 규칙이 없는 것은 의도된 것이다 — {@code /users/**} 는
 * {@code anyRequest().authenticated()} 로 떨어지고, 그 뒤 <b>누구의 것인지</b>는 위 대조가 정한다.
 * 장바구니({@code CartController})와 같은 구조다.
 */
@Tag(name = "Wishlist", description = "찜(위시리스트)")
@RestController
@RequestMapping("/users/{userId}/wishlist")
public class WishlistController {

    private final WishlistUseCase wishlistUseCase;

    public WishlistController(WishlistUseCase wishlistUseCase) {
        this.wishlistUseCase = wishlistUseCase;
    }

    @Operation(summary = "찜 목록 조회",
            description = "품절·단종·삭제된 상품도 사유(reason)와 함께 그대로 포함된다. 거르지 않는다.")
    @GetMapping
    public ResponseEntity<WishlistResponse> list(@PathVariable Long userId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        return ResponseEntity.ok(WishlistResponse.from(wishlistUseCase.list(userId)));
    }

    @Operation(summary = "찜 담기 (멱등)",
            description = "이미 담겨 있으면 아무것도 하지 않는다. 응답은 방금 한 동작이 아니라 결과 상태다.")
    @PutMapping("/products/{productId}")
    public ResponseEntity<MutationResponse> add(@PathVariable Long userId,
                                                @PathVariable Long productId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        return ResponseEntity.ok(MutationResponse.from(wishlistUseCase.add(userId, productId)));
    }

    @Operation(summary = "찜 빼기 (멱등)",
            description = "담겨 있지 않아도 200 이다 — 사용자가 원한 결과가 이미 성립해 있다.")
    @DeleteMapping("/products/{productId}")
    public ResponseEntity<MutationResponse> remove(@PathVariable Long userId,
                                                   @PathVariable Long productId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        return ResponseEntity.ok(MutationResponse.from(wishlistUseCase.remove(userId, productId)));
    }

    @Operation(summary = "찜 여부 단건 조회",
            description = "상품 화면의 하트 표시용. 목록 전체를 읽지 않는다.")
    @GetMapping("/products/{productId}")
    public ResponseEntity<ContainsResponse> contains(@PathVariable Long userId,
                                                     @PathVariable Long productId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        return ResponseEntity.ok(new ContainsResponse(productId,
                wishlistUseCase.contains(userId, productId)));
    }

    @Operation(summary = "되살아나지 않을 항목 일괄 정리",
            description = "단종·삭제된 상품만 지운다. 품절은 남긴다 — 재입고를 기다리는 것이 찜의 목적이다. "
                    + "응답은 개수가 아니라 지워진 목록이라, 화면이 무엇을 지웠는지 말할 수 있다.")
    @DeleteMapping("/gone")
    public ResponseEntity<PurgeResponse> purgeGone(@PathVariable Long userId) {
        ResourceOwnership.requireSelfOrAdmin(userId);
        WishlistUseCase.PurgeResult result = wishlistUseCase.purgeGone(userId);
        return ResponseEntity.ok(new PurgeResponse(
                result.removed().stream().map(WishlistItemResponse::from).toList(),
                WishlistResponse.from(result.remaining())));
    }

    /**
     * 목록 응답.
     *
     * @param items      전체 항목(거르지 않음)
     * @param totalCount 전체 개수
     * @param goneCount  일괄 정리로 사라질 개수. 버튼을 띄울지 판단하는 근거이며,
     *                   화면은 이 숫자만으로 지우지 않고 해당 항목들을 먼저 보여 준다
     * @param maxItems   보관 상한. 한도 근처에서 미리 알릴 수 있도록 함께 준다
     */
    public record WishlistResponse(List<WishlistItemResponse> items,
                                   int totalCount,
                                   int goneCount,
                                   int maxItems) {
        static WishlistResponse from(Wishlist wishlist) {
            return new WishlistResponse(
                    wishlist.entries().stream().map(WishlistItemResponse::from).toList(),
                    wishlist.size(),
                    wishlist.gone().size(),
                    Wishlist.MAX_ITEMS);
        }
    }

    /**
     * 항목 한 줄.
     *
     * @param availability 상태 enum(AVAILABLE·OUT_OF_STOCK·NOT_SELLING·DISCONTINUED·REMOVED)
     * @param reason       사용자에게 보여 줄 한글 사유. 화면이 enum 을 다시 한글로 번역하지 않게 함께 준다
     * @param gone         일괄 정리 대상인가. 품절은 여기 포함되지 않는다
     */
    public record WishlistItemResponse(Long productId,
                                       String name,
                                       BigDecimal price,
                                       String primaryImageUrl,
                                       String availability,
                                       String reason,
                                       boolean available,
                                       boolean gone,
                                       LocalDateTime addedAt) {
        static WishlistItemResponse from(WishlistEntry entry) {
            return new WishlistItemResponse(
                    entry.productId(),
                    entry.product().name(),
                    entry.product().price(),
                    entry.product().primaryImageUrl(),
                    entry.product().availability().name(),
                    entry.reason(),
                    entry.isAvailable(),
                    entry.isGone(),
                    entry.item().addedAt());
        }
    }

    /**
     * 담기·빼기 결과.
     *
     * @param wished  호출이 끝난 뒤 담겨 있는가(결과 상태)
     * @param changed 이번 호출로 실제로 바뀌었는가
     * @param count   호출이 끝난 뒤 총 개수
     */
    public record MutationResponse(boolean wished, boolean changed, int count) {
        static MutationResponse from(WishlistUseCase.Mutation m) {
            return new MutationResponse(m.present(), m.changed(), m.size());
        }
    }

    public record ContainsResponse(Long productId, boolean wished) {}

    public record PurgeResponse(List<WishlistItemResponse> removed, WishlistResponse wishlist) {}
}
