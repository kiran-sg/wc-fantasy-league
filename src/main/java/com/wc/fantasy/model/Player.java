package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "players")
public class Player {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String position; // GK, DEF, MID, FWD
    @ManyToOne
    @JoinColumn(name = "team_id")
    private Team team;
    private Integer jerseyNumber;
    private String photoUrl;
}
