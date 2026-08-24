package github.lms.lemuel.operation.board.application.port.out;

import github.lms.lemuel.operation.board.domain.DetectedFileType;

/**
 * 바이트를 보고 실제 형식을 판정한다.
 *
 * <p>매직바이트 표는 <b>바깥 세상의 지식</b>이고 형식이 늘어나면 바뀐다 — 그래서 도메인이 아니라
 * 포트다. 도메인이 아는 것은 "판정 결과가 선언과 같은가"까지다.
 */
public interface DetectFileTypePort {

    /** 인식하지 못하면 {@link DetectedFileType#unknown()}. 예외를 던지지 않는다 — 모름도 결과다. */
    DetectedFileType detect(byte[] content);
}
