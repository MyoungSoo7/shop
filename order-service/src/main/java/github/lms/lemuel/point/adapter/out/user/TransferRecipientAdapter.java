package github.lms.lemuel.point.adapter.out.user;

import github.lms.lemuel.point.application.port.out.LoadTransferRecipientPort;
import github.lms.lemuel.user.application.port.out.LoadUserPort;
import github.lms.lemuel.user.domain.User;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 선물 받는 이 조회 — user 슬라이스로의 유일한 창구.
 *
 * <p>{@code order.adapter.out.user.UserExistenceAdapter} 와 같은 형태다. 내 슬라이스의 포트를
 * 내 슬라이스의 어댑터가 구현하고, 그 안에서만 남의 포트를 부른다. 의존 방향이 point → user
 * 한 방향으로 고정되므로 ArchUnit 의 슬라이스 순환 금지에 걸릴 일이 없다.
 *
 * <p><b>비교 규칙</b>: 두 값 모두 앞뒤 공백만 걷어 내고 그대로 비교한다. 이메일을 소문자로
 * 접지 않는 이유는 이 저장소가 이메일을 <b>입력한 그대로</b> 저장하기 때문이다 — 로그인도 같은
 * {@code findByEmail} 을 raw 값으로 부른다. 여기서만 접으면 대문자로 가입한 회원을 찾지 못하고,
 * 그 실패는 "받는 분을 확인할 수 없습니다"로 뭉개져 원인이 드러나지 않는다. 이름을 느슨하게
 * 맞추지 않는 것도 의도다 — 이 칸이 있는 이유는 이메일 오타로 남에게 돈이 가는 것을 막는 것이다.
 *
 * <p>서비스를 이용할 수 없는 회원(비활성·미승인·정지)은 받는 이가 될 수 없다. 받을 수 없는
 * 계정으로 보내면 포인트가 열리지 않는 계정에 갇힌다.
 */
@Component
public class TransferRecipientAdapter implements LoadTransferRecipientPort {

    private final LoadUserPort loadUserPort;

    public TransferRecipientAdapter(LoadUserPort loadUserPort) {
        this.loadUserPort = loadUserPort;
    }

    @Override
    public Optional<Recipient> findActiveRecipient(String email, String name) {
        if (email == null || email.isBlank() || name == null || name.isBlank()) {
            return Optional.empty();
        }
        return loadUserPort.findByEmail(email.strip())
                .filter(user -> matchesName(user, name))
                .filter(TransferRecipientAdapter::canReceive)
                .map(user -> new Recipient(user.getId(), user.getName()));
    }

    @Override
    public Optional<String> findNameById(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return loadUserPort.findById(userId).map(User::getName);
    }

    private static boolean matchesName(User user, String name) {
        return user.getName() != null && user.getName().equals(name.strip());
    }

    private static boolean canReceive(User user) {
        return user.isActive()
                && user.getMembershipStatus() != null
                && user.getMembershipStatus().canUseService();
    }
}
