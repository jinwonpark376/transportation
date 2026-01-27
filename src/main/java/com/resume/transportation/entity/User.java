package com.resume.transportation.entity;

import com.resume.transportation.enums.Location;
import com.resume.transportation.enums.UserRole;
import jakarta.persistence.*;
import lombok.Getter;
//TODO -- https://chatgpt.com/c/69744d1b-f8ec-8321-bd1d-b6c97fa5ea3d

@Getter
@Entity
@Table(name = "users",
        indexes = {
                @Index(name = "idx_user_role", columnList = "role")
        })
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role; // OPERATOR, VOLUNTEER

    @Column(nullable = false, length = 50)
    private String name;

    @Enumerated(EnumType.STRING)
    private Location baseLocation;


    protected User() {
    }

    public User(UserRole role, String name) {
        this.role = role;
        this.name = name;
        this.baseLocation = Location.AIRPORT; // 기본값
    }

    public void setBaseLocation(Location baseLocation) {
        this.baseLocation = baseLocation;
    }
}