package github.lms.lemuel.cart.adapter.out.redis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import github.lms.lemuel.cart.domain.Cart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisCartAdapter — Redis JSON 장바구니 영속성 (+ RedisCart 변환)")
class RedisCartAdapterTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    private ObjectMapper objectMapper;
    private RedisCartAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        adapter = new RedisCartAdapter(redis, objectMapper);
    }

    private Cart sampleCart() {
        Cart cart = Cart.createEmpty(42L);
        cart.addItem(1001L, 2001L, 3);
        return cart;
    }

    @Test
    @DisplayName("loadByUserId — 키 없으면 empty")
    void loadByUserId_miss() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("cart:42")).thenReturn(null);
        assertThat(adapter.loadByUserId(42L)).isEmpty();
    }

    @Test
    @DisplayName("save 후 loadByUserId — 왕복 직렬화 복원")
    void save_thenLoad_roundTrip() throws Exception {
        when(redis.opsForValue()).thenReturn(valueOps);

        Cart saved = adapter.save(sampleCart());
        assertThat(saved.getUserId()).isEqualTo(42L);
        assertThat(saved.getItems()).hasSize(1);
        verify(valueOps).set(eq("cart:42"), any(String.class), any(Duration.class));

        String json = objectMapper.writeValueAsString(RedisCart.from(sampleCart()));
        when(valueOps.get("cart:42")).thenReturn(json);

        Optional<Cart> loaded = adapter.loadByUserId(42L);
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getUserId()).isEqualTo(42L);
        assertThat(loaded.get().getItems().get(0).getProductId()).isEqualTo(1001L);
        assertThat(loaded.get().getItems().get(0).getQuantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("RedisCart.from → toDomain 변환 무결성")
    void redisCartConversion() {
        RedisCart rc = RedisCart.from(sampleCart());
        assertThat(rc.userId()).isEqualTo(42L);
        assertThat(rc.items()).hasSize(1);
        Cart back = rc.toDomain();
        assertThat(back.getUserId()).isEqualTo(42L);
        assertThat(back.getItems().get(0).getVariantId()).isEqualTo(2001L);
    }
}
