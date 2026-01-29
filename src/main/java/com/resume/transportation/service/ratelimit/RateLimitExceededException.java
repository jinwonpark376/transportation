package com.resume.transportation.service.ratelimit;

/**
 * Rate Limit 초과 시 발생하는 예외
 * 
 * HTTP 429 (Too Many Requests)로 매핑될 수 있음
 */
public class RateLimitExceededException extends RuntimeException {
    
    public RateLimitExceededException(String message) {
        super(message);
    }
}
