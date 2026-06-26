package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "user_teams")
public class UserTeam {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne
    @JoinColumn(name = "user_id", unique = true)
    private AppUser user;

    private String stage = "R32";

    @ManyToMany
    @JoinTable(name = "user_team_starters",
            joinColumns = @JoinColumn(name = "team_id"),
            inverseJoinColumns = @JoinColumn(name = "player_id"))
    @OrderColumn(name = "slot_order")
    private List<Player> starters = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "user_team_bench",
            joinColumns = @JoinColumn(name = "team_id"),
            inverseJoinColumns = @JoinColumn(name = "player_id"))
    @OrderColumn(name = "bench_order")
    private List<Player> bench = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "captain_id")
    private Player captain;

    @ManyToOne
    @JoinColumn(name = "vice_captain_id")
    private Player viceCaptain;

    private String formation = "4-4-2";
    private Boolean manualChangesMade = false;
    private Integer transfersMadeThisStage = 0;
    private Integer penaltyPoints = 0;
}
