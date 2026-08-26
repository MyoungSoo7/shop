package github.lms.lemuel.operation.education.domain.exception;

/** 지웠거나 쉬는 강사에 대한 조작 거부. 어댑터가 400 으로 번역한다. */
public class InvalidLecturerStateException extends RuntimeException {
    public InvalidLecturerStateException(String message) { super(message); }
}
