package github.lms.lemuel.order.adapter.out.lock;

import github.lms.lemuel.order.application.port.out.LockAcquisitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("RedisDistributedLockAdapter — SETNX+Lua 분산 락")
class RedisDistributedLockAdapterTest {

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;

    @Test
    @DisplayName("락 획득 성공 시 action 실행 후 해제")
    void executeWithLock_success() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:k1"), any(), any(Duration.class))).thenReturn(true);
        RedisDistributedLockAdapter adapter = new RedisDistributedLockAdapter(redis);

        String result = adapter.executeWithLock("k1", Duration.ofSeconds(1), Duration.ofSeconds(5), () -> "done");

        assertThat(result).isEqualTo("done");
        verify(redis).execute(any(RedisScript.class), anyList(), any());
    }

    @Test
    @DisplayName("대기 시간 초과 시 LockAcquisitionException")
    void executeWithLock_timeout() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:k2"), any(), any(Duration.class))).thenReturn(false);
        RedisDistributedLockAdapter adapter = new RedisDistributedLockAdapter(redis);

        assertThatThrownBy(() -> adapter.executeWithLock("k2", Duration.ZERO, Duration.ofSeconds(5), () -> "x"))
                .isInstanceOf(LockAcquisitionException.class);
    }

    @Test
    @DisplayName("해제 중 예외는 삼켜지고 action 결과는 정상 반환")
    void executeWithLock_releaseFailsSwallowed() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(eq("lock:k3"), any(), any(Duration.class))).thenReturn(true);
        doThrow(new RuntimeException("redis down")).when(redis).execute(any(RedisScript.class), anyList(), any());
        RedisDistributedLockAdapter adapter = new RedisDistributedLockAdapter(redis);

        String result = adapter.executeWithLock("k3", Duration.ofSeconds(1), Duration.ofSeconds(5), () -> "ok");
        assertThat(result).isEqualTo("ok");
    }
}
