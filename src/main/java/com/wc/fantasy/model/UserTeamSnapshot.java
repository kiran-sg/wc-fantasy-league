package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
@Entity
@Table(name = "user_team_snapshots",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "stage"}))
public class UserTeamSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id")
    private AppUser user;

    private String stage;
    private String formation = "4-4-2";

    @ManyToMany
    @JoinTable(name = "snapshot_starters",
            joinColumns = @JoinColumn(name = "snapshot_id"),
            inverseJoinColumns = @JoinColumn(name = "player_id"))
    @OrderColumn(name = "slot_order")
    private List<Player> starters = new ArrayList<>();

    @ManyToMany
    @JoinTable(name = "snapshot_bench",
            joinColumns = @JoinColumn(name = "snapshot_id"),
            inverseJoinColumns = @JoinColumn(name = "player_id"))
    @OrderColumn(name = "bench_order")
    private List<Player> bench = new ArrayList<>();

    @ManyToOne
    @JoinColumn(name = "captain_id")
    private Player captain;

    @ManyToOne
    @JoinColumn(name = "vice_captain_id")
    private Player viceCaptain;
}
