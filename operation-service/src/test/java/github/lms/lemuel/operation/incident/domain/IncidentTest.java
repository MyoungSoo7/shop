package github.lms.lemuel.operation.incident.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IncidentTest {

    private static final Instant T0 = Instant.parse("2026-07-06T05:00:00Z");
    private static final Duration SUPPRESSION = Duration.ofMinutes(30);

    private Incident openIncident() {
        return Incident.openFromAlert(
                "fp-1", SignalCategory.KAFKA_BACKLOG, IncidentSeverity.WARNING,
                "OutboxPendingBacklog", "Outbox PENDING 적체", "outbox",
                Map.of("component", "outbox"), Map.of("summary", "적체"),
                T0.minusSeconds(60), T0);
    }

    @Nested
    @DisplayName("openFromAlert")
    class OpenFromAlert {

        @Test
        void 신규_인시던트는_OPEN_상태로_열리고_startsAt_을_firstSeenAt_으로_보존한다() {
            Incident incident = openIncident();

            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.OPEN);
            assertThat(incident.getSource()).isEqualTo(IncidentSource.ALERTMANAGER);
            assertThat(incident.getFirstSeenAt()).isEqualTo(T0.minusSeconds(60));
            assertThat(incident.getLastSeenAt()).isEqualTo(T0);
            assertThat(incident.getOccurrenceCount()).isEqualTo(1);
            assertThat(incident.isActive()).isTrue();
        }

        @Test
        void startsAt_이_없으면_now_로_대체한다() {
            Incident incident = Incident.openFromAlert(
                    "fp-2", SignalCategory.UNKNOWN, IncidentSeverity.WARNING,
                    "t", null, null, null, null, null, T0);

            assertThat(incident.getFirstSeenAt()).isEqualTo(T0);
            assertThat(incident.getLabels()).isEmpty();
            assertThat(incident.getAnnotations()).isEmpty();
        }
    }

    @Nested
    @DisplayName("상태머신")
    class StateMachine {

        @Test
        void OPEN_에서_ACKNOWLEDGED_RESOLVED_FALSE_POSITIVE_전이를_허용한다() {
            assertThat(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.ACKNOWLEDGED)).isTrue();
            assertThat(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.RESOLVED)).isTrue();
            assertThat(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.FALSE_POSITIVE)).isTrue();
            assertThat(IncidentStatus.OPEN.canTransitionTo(IncidentStatus.OPEN)).isFalse();
        }

        @Test
        void ACKNOWLEDGED_에서_RESOLVED_FALSE_POSITIVE_전이를_허용하고_OPEN_복귀는_차단한다() {
            assertThat(IncidentStatus.ACKNOWLEDGED.canTransitionTo(IncidentStatus.RESOLVED)).isTrue();
            assertThat(IncidentStatus.ACKNOWLEDGED.canTransitionTo(IncidentStatus.FALSE_POSITIVE)).isTrue();
            assertThat(IncidentStatus.ACKNOWLEDGED.canTransitionTo(IncidentStatus.OPEN)).isFalse();
        }

        @Test
        void 터미널_상태는_모든_재전이를_차단한다() {
            for (IncidentStatus terminal : new IncidentStatus[]{IncidentStatus.RESOLVED, IncidentStatus.FALSE_POSITIVE}) {
                for (IncidentStatus target : IncidentStatus.values()) {
                    assertThat(terminal.canTransitionTo(target))
                            .as("%s → %s", terminal, target)
                            .isFalse();
                }
                assertThat(terminal.isActive()).isFalse();
            }
        }

        @Test
        void 해제된_인시던트를_다시_ack_하면_예외가_발생한다() {
            Incident incident = openIncident();
            incident.resolve("admin", T0.plusSeconds(10));

            assertThatThrownBy(() -> incident.acknowledge("admin", T0.plusSeconds(20)))
                    .isInstanceOf(InvalidIncidentTransitionException.class)
                    .hasMessageContaining("RESOLVED");
        }
    }

    @Nested
    @DisplayName("운영자 전이")
    class OperatorTransitions {

        @Test
        void ack_후_resolve_흐름에서_각_시각과_actor_가_기록된다() {
            Incident incident = openIncident();

            incident.acknowledge("admin@lemuel", T0.plusSeconds(10));
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.ACKNOWLEDGED);
            assertThat(incident.getAcknowledgedBy()).isEqualTo("admin@lemuel");
            assertThat(incident.getAcknowledgedAt()).isEqualTo(T0.plusSeconds(10));
            assertThat(incident.isActive()).isTrue();

            incident.resolve("admin@lemuel", T0.plusSeconds(20));
            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(incident.getResolvedBy()).isEqualTo("admin@lemuel");
            assertThat(incident.isActive()).isFalse();
        }

        @Test
        void 오탐_처리는_FALSE_POSITIVE_로_전이하고_actor_를_기록한다() {
            Incident incident = openIncident();

            incident.markFalsePositive("admin", T0.plusSeconds(5));

            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.FALSE_POSITIVE);
            assertThat(incident.getResolvedBy()).isEqualTo("admin");
        }

        @Test
        void 자동해제는_ACKNOWLEDGED_상태에서도_허용되고_actor_는_alertmanager_다() {
            Incident incident = openIncident();
            incident.acknowledge("admin", T0.plusSeconds(10));

            incident.autoResolve(T0.plusSeconds(30));

            assertThat(incident.getStatus()).isEqualTo(IncidentStatus.RESOLVED);
            assertThat(incident.getResolvedBy()).isEqualTo(Incident.AUTO_ACTOR);
            assertThat(incident.getResolvedAt()).isEqualTo(T0.plusSeconds(30));
        }
    }

    @Nested
    @DisplayName("refire")
    class Refire {

        @Test
        void refire_는_lastSeenAt_과_occurrenceCount_를_갱신한다() {
            Incident incident = openIncident();

            Incident.RefireResult result = incident.refire(IncidentSeverity.WARNING, T0.plusSeconds(300), SUPPRESSION);

            assertThat(incident.getLastSeenAt()).isEqualTo(T0.plusSeconds(300));
            assertThat(incident.getOccurrenceCount()).isEqualTo(2);
            assertThat(result.timelineLogged()).isTrue();   // 최초 refire 는 기록
        }

        @Test
        void 억제_간격_내_반복_refire_는_타임라인을_기록하지_않는다() {
            Incident incident = openIncident();

            incident.refire(IncidentSeverity.WARNING, T0.plus(Duration.ofMinutes(5)), SUPPRESSION);
            Incident.RefireResult second = incident.refire(IncidentSeverity.WARNING, T0.plus(Duration.ofMinutes(10)), SUPPRESSION);
            Incident.RefireResult third = incident.refire(IncidentSeverity.WARNING, T0.plus(Duration.ofMinutes(36)), SUPPRESSION);

            assertThat(second.timelineLogged()).isFalse();  // 5분 → 10분: 30분 미경과
            assertThat(third.timelineLogged()).isTrue();    // 5분 → 36분: 30분 경과
            assertThat(incident.getOccurrenceCount()).isEqualTo(4);
        }

        @Test
        void 심각도_승격은_억제_간격과_무관하게_즉시_기록되고_하향은_무시된다() {
            Incident incident = openIncident();
            incident.refire(IncidentSeverity.WARNING, T0.plus(Duration.ofMinutes(5)), SUPPRESSION);

            Incident.RefireResult upgraded = incident.refire(IncidentSeverity.CRITICAL, T0.plus(Duration.ofMinutes(6)), SUPPRESSION);
            assertThat(upgraded.timelineLogged()).isTrue();
            assertThat(upgraded.severityUpgraded()).isTrue();
            assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL);

            Incident.RefireResult downgradeAttempt = incident.refire(IncidentSeverity.WARNING, T0.plus(Duration.ofMinutes(7)), SUPPRESSION);
            assertThat(downgradeAttempt.severityUpgraded()).isFalse();
            assertThat(incident.getSeverity()).isEqualTo(IncidentSeverity.CRITICAL); // 하향 무시
        }

        @Test
        void 비활성_인시던트에_refire_하면_예외가_발생한다() {
            Incident incident = openIncident();
            incident.resolve("admin", T0.plusSeconds(10));

            assertThatThrownBy(() -> incident.refire(IncidentSeverity.WARNING, T0.plusSeconds(20), SUPPRESSION))
                    .isInstanceOf(InvalidIncidentTransitionException.class);
        }
    }

    @Nested
    @DisplayName("IncidentSeverity")
    class SeverityTest {

        @Test
        void 라벨_문자열_파싱은_대소문자를_무시하고_미지의_값은_WARNING_으로_폴백한다() {
            assertThat(IncidentSeverity.fromLabel("critical")).isEqualTo(IncidentSeverity.CRITICAL);
            assertThat(IncidentSeverity.fromLabel("CRITICAL")).isEqualTo(IncidentSeverity.CRITICAL);
            assertThat(IncidentSeverity.fromLabel("info")).isEqualTo(IncidentSeverity.INFO);
            assertThat(IncidentSeverity.fromLabel("warning")).isEqualTo(IncidentSeverity.WARNING);
            assertThat(IncidentSeverity.fromLabel("page")).isEqualTo(IncidentSeverity.WARNING);
            assertThat(IncidentSeverity.fromLabel(null)).isEqualTo(IncidentSeverity.WARNING);
            assertThat(IncidentSeverity.fromLabel(" ")).isEqualTo(IncidentSeverity.WARNING);
        }

        @Test
        void 심각도_순위는_CRITICAL_WARNING_INFO_순이다() {
            assertThat(IncidentSeverity.CRITICAL.isHigherThan(IncidentSeverity.WARNING)).isTrue();
            assertThat(IncidentSeverity.WARNING.isHigherThan(IncidentSeverity.INFO)).isTrue();
            assertThat(IncidentSeverity.WARNING.isHigherThan(IncidentSeverity.CRITICAL)).isFalse();
            assertThat(IncidentSeverity.WARNING.isHigherThan(IncidentSeverity.WARNING)).isFalse();
        }
    }
}
