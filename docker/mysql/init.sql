-- MySQL 초기화 스크립트
-- 성능 모니터링을 위한 설정

-- 슬로우 쿼리 로그 활성화
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 0.1;  -- 100ms 이상 쿼리 로깅

-- Performance Schema 활성화 확인
SELECT @@performance_schema;

-- 모니터링용 권한 부여 (MySQL Exporter용)
CREATE USER IF NOT EXISTS 'exporter'@'%' IDENTIFIED BY 'exporter123';
GRANT PROCESS, REPLICATION CLIENT, SELECT ON *.* TO 'exporter'@'%';
FLUSH PRIVILEGES;

-- 테스트 데이터 확인용 뷰 (선택사항)
-- CREATE VIEW reservation_stats AS
-- SELECT 
--     DATE(startTime) as reservation_date,
--     COUNT(*) as total_count,
--     COUNT(CASE WHEN status = 'CREATED' THEN 1 END) as created_count,
--     COUNT(CASE WHEN status = 'IN_PROGRESS' THEN 1 END) as in_progress_count
-- FROM reservation
-- GROUP BY DATE(startTime);
