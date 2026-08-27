package github.lms.lemuel.wishlist.application.service;

import github.lms.lemuel.wishlist.application.port.in.WishlistUseCase;
import github.lms.lemuel.wishlist.application.port.out.LoadWishlistPort;
import github.lms.lemuel.wishlist.application.port.out.LoadWishlistProductPort;
import github.lms.lemuel.wishlist.application.port.out.SaveWishlistPort;
import github.lms.lemuel.wishlist.domain.Wishlist;
import github.lms.lemuel.wishlist.domain.WishlistEntry;
import github.lms.lemuel.wishlist.domain.WishlistItem;
import github.lms.lemuel.wishlist.domain.WishlistProduct;
import github.lms.lemuel.wishlist.domain.exception.WishlistInvariantViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * 찜 운영.
 *
 * <p>여기서 지키는 것은 세 가지다.
 *
 * <p><b>1. 담기·빼기는 멱등이다.</b> 같은 요청이 두 번 와도 결과가 같다. 사전 조회로 중복을
 * 걸러 보지만, 그 조회와 저장 사이에 다른 요청이 끼어들 수 있으므로 <b>정본은 DB 유니크 제약</b>이다
 * (레거시는 사전 조회만 있고 제약이 없어 더블탭이 곧 중복 행이었다). 제약이 거부하면
 * "이미 담겨 있었다"로 번역한다 — 사용자가 원한 결과가 이미 성립했으므로 오류가 아니다.
 *
 * <p><b>2. 목록은 거르지 않는다.</b> 품절·단종·삭제도 사유를 붙여 그대로 돌려준다. 거르면
 * 사용자는 자기가 담아 둔 것이 말없이 사라지는 것을 본다.
 *
 * <p><b>3. 상품 조회는 한 번에 한다.</b> 항목마다 상품을 부르면 목록 한 장이 곧 N+1 이다.
 * 포트 자체가 일괄 조회 모양이라 루프로 부를 수 없다.
 */
@Service
@Transactional
public class WishlistService implements WishlistUseCase {

    private final LoadWishlistPort loadWishlistPort;
    private final SaveWishlistPort saveWishlistPort;
    private final LoadWishlistProductPort loadProductPort;

    public WishlistService(LoadWishlistPort loadWishlistPort,
                           SaveWishlistPort saveWishlistPort,
                           LoadWishlistProductPort loadProductPort) {
        this.loadWishlistPort = loadWishlistPort;
        this.saveWishlistPort = saveWishlistPort;
        this.loadProductPort = loadProductPort;
    }

    @Override
    @Transactional(readOnly = true)
    public Wishlist list(Long userId) {
        requireUser(userId);
        return hydrate(userId, loadWishlistPort.findByUserId(userId));
    }

    @Override
    public Mutation add(Long userId, Long productId) {
        requireUser(userId);
        requireProduct(productId);

        long size = loadWishlistPort.countByUserId(userId);
        if (loadWishlistPort.exists(userId, productId)) {
            // 이미 담겨 있다 — 아무것도 하지 않지만 결과는 사용자가 원한 그대로다.
            return new Mutation(true, false, (int) size);
        }
        if (size >= Wishlist.MAX_ITEMS) {
            throw new WishlistInvariantViolationException(
                    "찜은 최대 " + Wishlist.MAX_ITEMS + "개까지 담을 수 있습니다. 일부를 정리한 뒤 다시 시도하세요.");
        }

        try {
            saveWishlistPort.save(WishlistItem.add(userId, productId));
            return new Mutation(true, true, (int) size + 1);
        } catch (DataIntegrityViolationException e) {
            // 위 exists 와 여기 사이에 같은 요청이 먼저 들어왔다(더블탭·재시도). 유니크 제약이
            // 막아 준 것이므로 중복은 생기지 않았고, 사용자가 원한 상태는 이미 성립해 있다.
            return new Mutation(true, false, (int) loadWishlistPort.countByUserId(userId));
        }
    }

    @Override
    public Mutation remove(Long userId, Long productId) {
        requireUser(userId);
        requireProduct(productId);

        boolean changed = saveWishlistPort.deleteByUserIdAndProductId(userId, productId);
        return new Mutation(false, changed, (int) loadWishlistPort.countByUserId(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean contains(Long userId, Long productId) {
        requireUser(userId);
        requireProduct(productId);
        return loadWishlistPort.exists(userId, productId);
    }

    @Override
    public PurgeResult purgeGone(Long userId) {
        requireUser(userId);

        Wishlist current = hydrate(userId, loadWishlistPort.findByUserId(userId));
        List<WishlistEntry> gone = current.gone();
        if (gone.isEmpty()) {
            return new PurgeResult(List.of(), current);
        }

        List<Long> productIds = gone.stream().map(WishlistEntry::productId).toList();
        saveWishlistPort.deleteByUserIdAndProductIds(userId, productIds);

        List<WishlistEntry> remaining = current.entries().stream()
                .filter(e -> !productIds.contains(e.productId()))
                .toList();
        return new PurgeResult(gone, new Wishlist(userId, remaining));
    }

    /**
     * 찜 행들에 상품의 현재 모습을 붙인다.
     *
     * <p>상품 조회는 <b>한 번</b>이다. 조회 결과에 없는 상품은 삭제된 것으로 번역한다 — 조용히
     * 빼 버리면 목록 개수가 사용자가 담은 수와 어긋나고, 왜 줄었는지 설명할 방법이 없다.
     */
    private Wishlist hydrate(Long userId, List<WishlistItem> items) {
        if (items.isEmpty()) {
            return Wishlist.empty(userId);
        }
        List<Long> productIds = items.stream().map(WishlistItem::productId).distinct().toList();
        Map<Long, WishlistProduct> products = loadProductPort.findAllByIds(productIds);

        List<WishlistEntry> entries = items.stream()
                .map(item -> new WishlistEntry(item,
                        products.getOrDefault(item.productId(), WishlistProduct.removed(item.productId()))))
                .toList();
        return new Wishlist(userId, entries);
    }

    private static void requireUser(Long userId) {
        if (userId == null) {
            throw new WishlistInvariantViolationException("userId 필수");
        }
    }

    private static void requireProduct(Long productId) {
        if (productId == null) {
            throw new WishlistInvariantViolationException("productId 필수");
        }
    }
}
