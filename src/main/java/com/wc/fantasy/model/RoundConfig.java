package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "round_config")
public class RoundConfig {

    @Id
    @Column(nullable = false, length = 10)
    private String stage; // R32, R16, QF, SF, FINAL

    @Column(nullable = false)
    private Integer freeTransfers;

    @Column(nullable = false)
    private Integer countryLimit;

    @Column(nullable = false)
    private Integer windowOpenHour;   // 12

    @Column(nullable = false)
    private Integer windowCloseHour;  // 19

    @Column(nullable = false, length = 60)
    private String windowTimezone;    // Asia/Kolkata

    // First match kickoff of this round (UTC). Active stage = latest row where roundStart <= now.
    // Null = round not yet scheduled; treated as not yet active.
    private LocalDateTime roundStart;

    // Actual FIFA first-match kickoff for this round (IST). Used to determine Case B vs Case C window logic.
    private LocalDateTime fifaRoundStart;

    // Admin-set: true once this round's scores are settled. Immediately closes the transfer window for this round
    // and (when set on the previous round) opens the next round's window unconditionally.
    @Column(columnDefinition = "boolean default false")
    private Boolean isRoundClosed = false;
}
