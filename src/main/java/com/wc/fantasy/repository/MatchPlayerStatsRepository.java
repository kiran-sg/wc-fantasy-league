package com.wc.fantasy.repository;

import com.wc.fantasy.model.MatchPlayerStats;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MatchPlayerStatsRepository extends JpaRepository<MatchPlayerStats, Long> {
    List<MatchPlayerStats> findByMatchId(Long matchId);
    List<MatchPlayerStats> findByMatchIdAndPlayerIdIn(Long matchId, List<Long> playerIds);
}
