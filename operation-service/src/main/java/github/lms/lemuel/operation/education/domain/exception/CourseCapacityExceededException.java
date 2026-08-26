package github.lms.lemuel.operation.education.domain.exception;

public class CourseCapacityExceededException extends RuntimeException {
    public CourseCapacityExceededException(String message) { super(message); }
}
