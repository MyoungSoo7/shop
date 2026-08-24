package github.lms.lemuel.operation.education;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        classes = github.lms.lemuel.OperationServiceApplication.class,
        properties = {
                "spring.flyway.enabled=false",
                "spring.jpa.hibernate.ddl-auto=none",
                "spring.datasource.url=jdbc:h2:mem:education;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.username=sa",
                "spring.datasource.password="
        })
class EducationServiceApplicationTest {

    @Test
    void applicationContextStartsWithOperationBootstrap() {
    }
}
