package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "teams")
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String code;
    @Column(name = "team_group")
    private String group;
    private String flagUrl;
    @Column(name = "eliminated", columnDefinition = "boolean default false")
    private Boolean eliminated = false;
}
