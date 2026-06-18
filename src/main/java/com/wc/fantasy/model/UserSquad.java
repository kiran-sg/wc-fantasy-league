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
    private Integer pointsEarned = 0;
    private Boolean locked = false;
}
