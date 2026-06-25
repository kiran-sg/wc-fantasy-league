package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.List;

@Data
@Entity
@Table(name = "user_squads", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "match_id"}))
public class UserSquad {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "user_id")
    private AppUser user;
    @ManyToOne
    @JoinColumn(name = "match_id")
    private Match match;
    @ManyToMany
    @JoinTable(name = "squad_players",
            joinColumns = @JoinColumn(name = "squad_id"),
            inverseJoinColumns = @JoinColumn(name = "player_id"))
    private List<Player> players;
    @ManyToOne
    @JoinColumn(name = "captain_id")
    private Player captain;
    @ManyToOne
    @JoinColumn(name = "vice_captain_id")
    private Player viceCaptain;
    @ManyToMany
    @JoinTable(name = "squad_bench",
            joinColumns = @JoinColumn(name = "squad_id"),
            inverseJoinColumns = @JoinColumn(name = "player_id"))
    @OrderColumn(name = "bench_order")
    private List<Player> bench = new java.util.ArrayList<>();
    private Boolean manualChangesMade = false;
    private Integer pointsEarned = 0;
    private Boolean locked = false;
}
