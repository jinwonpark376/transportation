package com.resume.transportation.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시간 겹침 로직 검증 테스트
 */
class OverlapLogicTest {

    @Test
    @DisplayName("시간 겹침 로직 검증: AND vs OR")
    void testOverlapLogic() {
        // Given: 기존 예약 [10:00 ~ 12:00]
        LocalDateTime existingStart = LocalDateTime.of(2024, 1, 1, 10, 0);
        LocalDateTime existingEnd = LocalDateTime.of(2024, 1, 1, 12, 0);

        // Case 1: 완전히 겹침 [10:30 ~ 11:30]
        testCase("완전히 겹침", 
                existingStart, existingEnd,
                LocalDateTime.of(2024, 1, 1, 10, 30),
                LocalDateTime.of(2024, 1, 1, 11, 30),
                true); // 겹침 O

        // Case 2: 앞부분만 겹침 [09:00 ~ 11:00]
        testCase("앞부분만 겹침",
                existingStart, existingEnd,
                LocalDateTime.of(2024, 1, 1, 9, 0),
                LocalDateTime.of(2024, 1, 1, 11, 0),
                true); // 겹침 O

        // Case 3: 뒷부분만 겹침 [11:00 ~ 13:00]
        testCase("뒷부분만 겹침",
                existingStart, existingEnd,
                LocalDateTime.of(2024, 1, 1, 11, 0),
                LocalDateTime.of(2024, 1, 1, 13, 0),
                true); // 겹침 O

        // Case 4: 완전히 감싸는 경우 [09:00 ~ 13:00]
        testCase("완전히 감싸는 경우",
                existingStart, existingEnd,
                LocalDateTime.of(2024, 1, 1, 9, 0),
                LocalDateTime.of(2024, 1, 1, 13, 0),
                true); // 겹침 O

        // Case 5: 완전히 이전 [08:00 ~ 09:00]
        testCase("완전히 이전",
                existingStart, existingEnd,
                LocalDateTime.of(2024, 1, 1, 8, 0),
                LocalDateTime.of(2024, 1, 1, 9, 0),
                false); // 겹침 X

        // Case 6: 완전히 이후 [13:00 ~ 14:00]
        testCase("완전히 이후",
                existingStart, existingEnd,
                LocalDateTime.of(2024, 1, 1, 13, 0),
                LocalDateTime.of(2024, 1, 1, 14, 0),
                false); // 겹침 X

        // Case 7: 경계 일치 (시작) [12:00 ~ 13:00]
        testCase("경계 일치 (시작)",
                existingStart, existingEnd,
                LocalDateTime.of(2024, 1, 1, 12, 0),
                LocalDateTime.of(2024, 1, 1, 13, 0),
                false); // 겹침 X (경계는 겹치지 않음)

        // Case 8: 경계 일치 (종료) [09:00 ~ 10:00]
        testCase("경계 일치 (종료)",
                existingStart, existingEnd,
                LocalDateTime.of(2024, 1, 1, 9, 0),
                LocalDateTime.of(2024, 1, 1, 10, 0),
                false); // 겹침 X (경계는 겹치지 않음)
    }

    private void testCase(String caseName, 
                         LocalDateTime existingStart, LocalDateTime existingEnd,
                         LocalDateTime newStart, LocalDateTime newEnd,
                         boolean shouldOverlap) {
        
        // 현재 코드 (AND)
        boolean overlapWithAnd = existingStart.isBefore(newEnd) && existingEnd.isAfter(newStart);
        
        // 제안된 코드 (OR) - 비교용
        boolean overlapWithOr = existingStart.isBefore(newEnd) || existingEnd.isAfter(newStart);
        
        System.out.println("=".repeat(60));
        System.out.println("테스트: " + caseName);
        System.out.println("기존: [" + existingStart.toLocalTime() + " ~ " + existingEnd.toLocalTime() + "]");
        System.out.println("새것: [" + newStart.toLocalTime() + " ~ " + newEnd.toLocalTime() + "]");
        System.out.println("기대값: " + (shouldOverlap ? "겹침" : "안겹침"));
        System.out.println("AND 결과: " + (overlapWithAnd ? "겹침" : "안겹침") + " " + (overlapWithAnd == shouldOverlap ? "✅" : "❌"));
        System.out.println("OR 결과: " + (overlapWithOr ? "겹침" : "안겹침") + " " + (overlapWithOr == shouldOverlap ? "✅" : "❌"));
        
        // AND 로직이 정확해야 함
        assertThat(overlapWithAnd)
                .as(caseName + " - AND 로직")
                .isEqualTo(shouldOverlap);
    }

    @Test
    @DisplayName("SQL 쿼리 조건 시뮬레이션")
    void testSqlCondition() {
        System.out.println("\n=== SQL 조건 시뮬레이션 ===\n");
        
        // 기존 예약: 10:00 ~ 12:00
        int rStartTime = 10;
        int rEndTime = 12;
        
        // 새 예약 시나리오
        int[][] newReservations = {
            {11, 13},  // 겹침 O: 뒷부분 겹침
            {9, 11},   // 겹침 O: 앞부분 겹침
            {8, 9},    // 겹침 X: 완전히 이전
            {13, 14},  // 겹침 X: 완전히 이후
            {12, 13},  // 겹침 X: 경계 일치
        };
        
        for (int[] newRes : newReservations) {
            int startTime = newRes[0];
            int endTime = newRes[1];
            
            // SQL: r.startTime < :endTime AND r.endTime > :startTime
            boolean overlap = (rStartTime < endTime) && (rEndTime > startTime);
            
            System.out.printf("기존[%d~%d] vs 새것[%d~%d] → %s%n",
                    rStartTime, rEndTime, startTime, endTime,
                    overlap ? "겹침 ✅" : "안겹침 ❌");
        }
    }
}
