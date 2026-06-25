package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;

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
    @Column(precision = 12, scale = 1)
    private BigDecimal price = BigDecimal.valueOf(6_000_000); // default $6M
    private String fifaPlayerName; // name from FIFA JSON for price mapping
}
