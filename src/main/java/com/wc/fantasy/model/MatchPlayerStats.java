package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "match_player_stats")
public class MatchPlayerStats {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "match_id")
    private Match match;
    @ManyToOne
    @JoinColumn(name = "player_id")
    private Player player;
    private Integer goals = 0;
    private Integer assists = 0;
    private Integer yellowCards = 0;
    private Integer redCards = 0;
    private Boolean cleanSheet = false;
    private Integer minutesPlayed = 0;
    private Boolean manOfMatch = false;
    private Integer totalPoints = 0;
}
