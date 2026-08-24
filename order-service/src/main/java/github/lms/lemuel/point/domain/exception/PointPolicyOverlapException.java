package github.lms.lemuel.point.domain.exception;

import github.lms.lemuel.common.exception.BusinessException;
import github.lms.lemuel.common.exception.ErrorCode;

/**
 * 같은 {@code (scope, scopeKey)} 안에서 적립률 정책 기간이 겹칠 때 (409).
 *
 * <p>버그가 아니라 <b>순서 안내</b>다: 요율을 바꾸려면 현재 정책의 종료일을 먼저 지정해
 * 자리를 비운 뒤 새 정책을 넣어야 한다(ADR 0032 — 행 UPDATE 금지, 종료 + 신규 등록).
 *
 * <p>판정의 정본은 DB 의 {@code ex_pep_no_overlap} GiST 배제 제약이다. 애플리케이션이 미리
 * 확인해도 그 사이에 다른 요청이 끼어들 수 있어 진짜 방어가 되지 못하므로, 제약 위반을
 * 이 예외로 번역만 한다 — 그대로 두면 catch-all 이 500 으로 올려 "서버 오류"처럼 보인다.
 */
public class PointPolicyOverlapException extends BusinessException {

    public PointPolicyOverlapException(String message) {
        super(ErrorCode.POINT_POLICY_PERIOD_OVERLAP, message);
    }
}
