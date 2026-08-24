#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# Kafka 컨슈머 Lag 실습용 부하 생성 스크립트
#
# Redpanda 컨테이너 내부에서 rpk 로 lemuel.payment.captured 토픽에 합성 결제완료
# 이벤트를 produce 한다. settlement 컨슈머가 인위 지연(APP_KAFKA_PRACTICE_CONSUMER_DELAY_MS)
# 으로 ~6건/s 밖에 못 처리하는 상태에서, 생산 속도를 그보다 빠르게 만들어
# consumer lag 을 발생시키는 것이 목적. → Grafana "Kafka Consumer Lag" 대시보드로 관찰.
#
# ▶ 실행 (호스트 PowerShell 에서, 파일을 컨테이너 sh 로 파이프):
#     docker exec -e RATE=25 -e DURATION=120 -i lemuel-redpanda sh < monitoring/practice/kafka-load.sh
#
#   localhost IPv6 함정 회피: 앱 HTTP 가 아니라 컨테이너 내부 rpk(브로커 로컬)로 직접 발행하므로
#   호스트 네트워크/포트를 타지 않는다.
#
# ▶ 파라미터 (env):
#     RATE      초당 메시지 수 (기본 25). 컨슈머 처리율(~6/s)보다 크게 둘 것.
#     DURATION  지속 시간(초) (기본 120)
#     TOPIC     대상 토픽 (기본 lemuel.payment.captured)
#
# ▶ 설계 메모:
#   - rpk 프로세스 스폰 비용을 줄이려고 1초에 RATE 개를 "한 번의 rpk produce" 로 묶어 발행한다.
#   - event_id 헤더는 배치(=1초)당 1개를 공유한다. 컨슈머의 멱등 체크(processed_events PK)는
#     인위 지연(Thread.sleep) "이후"에 수행되므로, 배치 내 2번째 메시지부터는
#     "지연만 소모하고 스킵" 된다 → 각 메시지가 동일하게 컨슈머 처리시간을 잡아먹어 lag 은 정확히 쌓인다.
#     (배치당 1건만 실제 정산 생성 → DB 과적재 없이 lag 곡선만 깔끔하게 본다.)
#   - 모든 메시지를 실제 정산으로 만들고 싶으면 RATE=1 처럼 작게 주거나, 메시지마다 고유 event_id 가
#     필요하다. 본 실습 목적(lag 관찰)에는 위 방식이 가장 안정적이다.
# ─────────────────────────────────────────────────────────────────────────────

RATE="${RATE:-25}"
DURATION="${DURATION:-120}"
TOPIC="${TOPIC:-lemuel.payment.captured}"

base=$(date +%s)
end=$(( base + DURATION ))
batch=0

echo "[load] topic=$TOPIC rate=${RATE}/s duration=${DURATION}s  (consumer ~6/s 이면 lag 약 $(( RATE - 6 ))/s 누적)"

while [ "$(date +%s)" -lt "$end" ]; do
  batch=$(( batch + 1 ))
  eid=$(cat /proc/sys/kernel/random/uuid)
  # 주의: '{ ... } | rpk' 의 좌측 블록은 서브셸이라 내부 변수는 부모로 전파되지 않는다.
  # 따라서 카운터(batch)는 부모 루프에서 증가시키고, paymentId 는 base/batch/n 으로 결정적으로 계산한다.
  n=0
  {
    while [ "$n" -lt "$RATE" ]; do
      n=$(( n + 1 ))
      # 전역 유니크 paymentId (settlements.payment_id UNIQUE 충돌 방지): base*1e6 + batch*1000 + n
      id=$(( base * 1000000 + batch * 1000 + n ))
      # sellerTier 동봉 → order DB 조인 없이 정산 생성 성공. sellerId 생략 → 다운스트림 발행 스킵(노이즈 0).
      printf '{"paymentId":%s,"orderId":%s,"amount":"10000","sellerTier":"NORMAL"}\n' "$id" "$id"
    done
  # -z none: rpk 기본 압축은 snappy 인데, settlement 컨테이너 이미지에 snappy 네이티브 의존(glibc ld-linux)이
  #          없어 컨슈머 fetch 가 실패한다(NoClassDefFoundError). 앱 자체 프로듀서는 압축 미사용이므로 동일하게 무압축 발행.
  } | rpk topic produce "$TOPIC" -z none -H "event_id:${eid}" >/dev/null 2>&1
  sent=$(( batch * RATE ))
  echo "[load] sent=${sent}  elapsed=$(( $(date +%s) - base ))s"
  sleep 1
done

sent=$(( batch * RATE ))

echo "[load] done. total sent=${sent}. 컨슈머가 다 비울 때까지 lag 이 천천히 0 으로 수렴한다."
