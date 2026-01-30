#!/bin/bash

# ============================================
# 부하 테스트 스크립트
# 사용법: ./scripts/load-test.sh [요청수] [동시성]
# 예: ./scripts/load-test.sh 100 10
# ============================================

BASE_URL="http://localhost:8080"
REQUESTS=${1:-100}
CONCURRENCY=${2:-10}

echo "=========================================="
echo "  Transportation 부하 테스트"
echo "=========================================="
echo "요청 수: $REQUESTS"
echo "동시성: $CONCURRENCY"
echo ""

# 1. 테스트 데이터 셋업
echo "1️⃣ 테스트 데이터 셋업..."
SETUP_RESPONSE=$(curl -s -X POST "$BASE_URL/api/test/setup?vehicles=10&dispatchers=20")
echo "$SETUP_RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$SETUP_RESPONSE"

# ID 추출
OPERATOR_ID=$(echo "$SETUP_RESPONSE" | python3 -c "import sys, json; print(json.load(sys.stdin)['operatorId'])" 2>/dev/null)
VEHICLE_IDS=$(echo "$SETUP_RESPONSE" | python3 -c "import sys, json; print(','.join(map(str, json.load(sys.stdin)['vehicleIds'])))" 2>/dev/null)
DISPATCHER_IDS=$(echo "$SETUP_RESPONSE" | python3 -c "import sys, json; print(','.join(map(str, json.load(sys.stdin)['dispatcherIds'])))" 2>/dev/null)

if [ -z "$OPERATOR_ID" ]; then
    echo "❌ 데이터 셋업 실패"
    exit 1
fi

echo ""
echo "operatorId: $OPERATOR_ID"
echo "vehicleIds: $VEHICLE_IDS"
echo "dispatcherIds: $DISPATCHER_IDS"
echo ""

# 2. 시스템 상태 확인
echo "2️⃣ 시스템 상태 확인..."
curl -s "$BASE_URL/api/reservations/status" | python3 -m json.tool 2>/dev/null
echo ""

# 3. 부하 테스트 실행
echo "3️⃣ 부하 테스트 시작..."
echo ""

# 시작 시간
START_TIME=$(date +%s%3N)

# 차량/디스패처 배열로 변환
IFS=',' read -ra VEHICLES <<< "$VEHICLE_IDS"
IFS=',' read -ra DISPATCHERS <<< "$DISPATCHER_IDS"

SUCCESS=0
FAILED=0

# 병렬 요청 실행
for i in $(seq 1 $REQUESTS); do
    # 순환 배정
    VEHICLE_ID=${VEHICLES[$((i % ${#VEHICLES[@]}))]}
    DISPATCHER_ID=${DISPATCHERS[$((i % ${#DISPATCHERS[@]}))]}
    
    # 시간 슬롯 (1시간 단위로 분산)
    HOUR_OFFSET=$((i % 12))
    START_HOUR=$((10 + HOUR_OFFSET))
    END_HOUR=$((START_HOUR + 2))
    
    # 날짜 (내일)
    DATE=$(date -v+1d +%Y-%m-%d 2>/dev/null || date -d "+1 day" +%Y-%m-%d)
    
    # 요청 본문
    BODY=$(cat <<EOF
{
  "operatorId": $OPERATOR_ID,
  "vehicleId": $VEHICLE_ID,
  "dispatcherId": $DISPATCHER_ID,
  "fromLocation": "AIRPORT",
  "toLocation": "HOTEL",
  "startTime": "${DATE}T${START_HOUR}:00:00",
  "endTime": "${DATE}T${END_HOUR}:00:00"
}
EOF
)
    
    # 비동기 요청
    (
        RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$BASE_URL/api/reservations" \
            -H "Content-Type: application/json" \
            -d "$BODY")
        
        HTTP_CODE=$(echo "$RESPONSE" | tail -1)
        
        if [ "$HTTP_CODE" = "200" ]; then
            echo "✅ Request $i: Success"
        else
            echo "❌ Request $i: Failed (HTTP $HTTP_CODE)"
        fi
    ) &
    
    # 동시성 제어
    if [ $((i % CONCURRENCY)) -eq 0 ]; then
        wait
    fi
done

# 모든 요청 완료 대기
wait

# 종료 시간
END_TIME=$(date +%s%3N)
DURATION=$((END_TIME - START_TIME))

echo ""
echo "=========================================="
echo "  테스트 완료"
echo "=========================================="
echo "소요 시간: ${DURATION}ms"

# 4. 결과 확인
echo ""
echo "4️⃣ 최종 통계..."
curl -s "$BASE_URL/api/test/stats" | python3 -m json.tool 2>/dev/null

echo ""
echo "5️⃣ 시스템 상태..."
curl -s "$BASE_URL/api/reservations/status" | python3 -m json.tool 2>/dev/null

echo ""
echo "=========================================="
echo "  Grafana에서 결과 확인: http://localhost:3000"
echo "=========================================="
