package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "app_users")
public class AppUser {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(unique = true)
    private String username;
    private String displayName;
    private Integer totalPoints = 0;

    @Column(name = "missed_points", nullable = false, columnDefinition = "integer default 0")
    private Integer missedPoints = 0;

    @Column(name = "is_admin", nullable = false, columnDefinition = "boolean default false")
    private Boolean isAdmin = false;

    @Column(name = "location")
    private String location;
}
