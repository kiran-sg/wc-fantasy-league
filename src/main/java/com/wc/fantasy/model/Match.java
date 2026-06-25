package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "matches")
public class Match {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne
    @JoinColumn(name = "team_a_id")
    private Team teamA;
    @ManyToOne
    @JoinColumn(name = "team_b_id")
    private Team teamB;
    private LocalDateTime matchTime;
    private String venue;
    private String stage; // GROUP, R16, QF, SF, FINAL
    private String status; // UPCOMING, LIVE, COMPLETED
    private Integer scoreA;
    private Integer scoreB;
    private String teamALabel; // TBD placeholder e.g. "Winner Group A"
    private String teamBLabel;
}
