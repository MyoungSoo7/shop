package github.lms.lemuel.operation.board.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 첨부 저장·청소 설정.
 *
 * @param baseDir            첨부 루트 디렉터리. 컨테이너에서는 볼륨을 여기에 마운트한다
 * @param cleanupEnabled     고아 청소 배치 사용 여부. 끄면 스케줄러가 아예 뜨지 않는다
 * @param cleanupCron        청소 주기(Asia/Seoul)
 * @param cleanupGraceHours  이 시간보다 최근에 만들어진 파일은 건드리지 않는다.
 *                           업로드는 "파일 저장 → DB 기록" 순서라 그 사이 정상 파일도 잠깐 고아처럼 보인다
 */
@ConfigurationProperties(prefix = "app.board.attachment")
public record AttachmentProperties(
        String baseDir,
        Boolean cleanupEnabled,
        String cleanupCron,
        Integer cleanupGraceHours) {

    public AttachmentProperties {
        if (baseDir == null || baseDir.isBlank()) {
            baseDir = "./data/board-attachments";
        }
        if (cleanupEnabled == null) {
            cleanupEnabled = Boolean.TRUE;
        }
        if (cleanupCron == null || cleanupCron.isBlank()) {
            // 매일 새벽 4시 10분 — 트래픽이 가장 적은 시간대.
            cleanupCron = "0 10 4 * * *";
        }
        // 유예를 0 이하로 두면 업로드 중인 파일을 지운다. 실수로도 그렇게 되지 않게 바닥을 깐다.
        if (cleanupGraceHours == null || cleanupGraceHours < 1) {
            cleanupGraceHours = 24;
        }
    }
}
