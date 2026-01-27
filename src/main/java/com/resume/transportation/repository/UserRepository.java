package com.resume.transportation.repository;

import com.resume.transportation.entity.User;
import com.resume.transportation.enums.Location;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    /**
     * User의 기준 위치 조회
     * (첫 예약 이전 위치 판단용)
     */
    @Query("""
        select u.baseLocation
        from User u
        where u.id = :userId
    """)
    Location findBaseLocation(@Param("userId") Long userId);
}
