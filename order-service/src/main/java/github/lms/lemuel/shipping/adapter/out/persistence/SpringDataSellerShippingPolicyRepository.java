package github.lms.lemuel.shipping.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface SpringDataSellerShippingPolicyRepository
        extends JpaRepository<SellerShippingPolicyJpaEntity, Long> {

    List<SellerShippingPolicyJpaEntity> findBySellerIdIn(Collection<Long> sellerIds);

    /** 운영 콘솔 목록 — 정렬을 쿼리에 고정한다(무정렬 findAll 은 DB 물리 순서라 화면이 매번 흔들린다). */
    List<SellerShippingPolicyJpaEntity> findAllByOrderBySellerIdAsc();
}
