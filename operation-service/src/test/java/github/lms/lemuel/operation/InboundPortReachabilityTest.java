package github.lms.lemuel.operation;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static github.lms.lemuel.common.arch.InboundPortReachability.reachableFromInboundAdapter;

/**
 * 인바운드 포트 도달 가능성 가드.
 *
 * <p>유스케이스를 구현하고 단위 테스트까지 붙였는데 <b>아무 어댑터도 호출하지 않아</b> 런타임에
 * 존재하지 않는 기능이 되는 결함을 막는다. 컴파일러도 단위 테스트도 "부르는 사람이 없다"를 보지 못한다.
 * 실제 사례: loan 담보 재평가·강제집행(마진콜 판정이 도달 불가), card 명세서 개시(청구 사이클 입력 부재).
 *
 * <p>판정은 <b>전이적</b>이다 — 어댑터가 포트를 간접 호출하는 정상 오케스트레이션
 * (폴러 → 아웃박스 서비스 → 원장 포트)을 위반으로 잡지 않기 위해서다.
 * shared-common 의 포트는 이 서비스의 책임이 아니므로 대상에서 제외한다.
 */
class InboundPortReachabilityTest {

    @Test
    void 모든_인바운드_포트는_어댑터에서_도달_가능하다() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("github.lms.lemuel.operation");

        ArchRule rule = classes()
                .that().resideInAPackage("..application.port.in..")
                .and().areInterfaces()
                .and().resideOutsideOfPackage("..common..")
                .should(reachableFromInboundAdapter(classes))
                .allowEmptyShould(true);

        rule.check(classes);
    }
}
