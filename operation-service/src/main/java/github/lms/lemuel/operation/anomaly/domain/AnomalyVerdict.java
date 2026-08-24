package github.lms.lemuel.operation.anomaly.domain;

/** 버킷 1개에 대한 판정 결과. */
public enum AnomalyVerdict {
    /** 정상 — 게이트(표본·상대임계·z) 중 하나라도 미충족. */
    NORMAL,
    /** 이상 — z >= zThreshold AND count_total >= minSample AND failureRate >= floor 모두 충족. */
    ANOMALY
}
