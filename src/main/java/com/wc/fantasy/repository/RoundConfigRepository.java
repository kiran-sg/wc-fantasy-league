package com.wc.fantasy.repository;

import com.wc.fantasy.model.RoundConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDateTime;
import java.util.Optional;

public interface RoundConfigRepository extends JpaRepository<RoundConfig, String> {

    // Latest round whose start time is in the past — this is the active round
    @Query("SELECT r FROM RoundConfig r WHERE r.roundStart IS NOT NULL AND r.roundStart <= :now ORDER BY r.roundStart DESC")
    java.util.List<RoundConfig> findStartedRounds(LocalDateTime now);

    default Optional<RoundConfig> findActiveRound() {
        // roundStart is stored as IST (matchTime is converted to IST on sync) — compare in IST
        var now = LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        var started = findStartedRounds(now);
        return started.isEmpty() ? Optional.empty() : Optional.of(started.get(0));
    }
}
