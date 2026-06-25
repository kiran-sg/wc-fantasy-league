package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "user_team_match_points",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_team_id", "match_id"}))
public class UserTeamMatchPoints {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_team_id")
    private UserTeam userTeam;

    @ManyToOne
    @JoinColumn(name = "match_id")
    private Match match;

    private Integer pointsEarned = 0;
    private String stage;
}
