package github.lms.lemuel.organization.application.service;

import github.lms.lemuel.organization.application.port.in.OrganizationCommandUseCase;
import github.lms.lemuel.organization.application.port.out.PublishOrganizationEventPort;
import github.lms.lemuel.organization.application.port.out.SaveMembershipPort;
import github.lms.lemuel.organization.application.port.out.SaveOrganizationPort;
import github.lms.lemuel.organization.domain.Membership;
import github.lms.lemuel.organization.domain.Organization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 조직 생성 서비스 — 조직 저장 + 생성자 OWNER 멤버십 등록 + 이벤트 발행을 한 트랜잭션으로 원자화한다.
 */
@Service
public class OrganizationCommandService implements OrganizationCommandUseCase {

    private static final Logger log = LoggerFactory.getLogger(OrganizationCommandService.class);

    private final SaveOrganizationPort saveOrganizationPort;
    private final SaveMembershipPort saveMembershipPort;
    private final PublishOrganizationEventPort publishPort;

    public OrganizationCommandService(SaveOrganizationPort saveOrganizationPort,
                                      SaveMembershipPort saveMembershipPort,
                                      PublishOrganizationEventPort publishPort) {
        this.saveOrganizationPort = saveOrganizationPort;
        this.saveMembershipPort = saveMembershipPort;
        this.publishPort = publishPort;
    }

    @Override
    @Transactional
    public Organization create(CreateOrganizationCommand command) {
        Organization saved = saveOrganizationPort.save(
                Organization.create(command.name(), command.type(), command.externalRef()));
        Membership owner = saveMembershipPort.save(Membership.owner(saved.getId(), command.actingUserId()));
        publishPort.publishCreated(saved, command.actingUserId());
        log.info("조직 생성: id={} name={} type={} owner={}",
                saved.getId(), saved.getName(), saved.getType(), owner.getUserId());
        return saved;
    }
}
