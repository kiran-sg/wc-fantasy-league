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
        var now = LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        var started = findStartedRounds(now);
        return started.isEmpty() ? Optional.empty() : Optional.of(started.get(0));
    }

    default Optional<RoundConfig> findPreviousRound(String currentStage) {
        // Canonical knockout stage order — does not include GROUP (no previous round before R32)
        java.util.List<String> ORDER = java.util.List.of("R32", "R16", "QF", "SF", "FINAL");
        int idx = ORDER.indexOf(currentStage.toUpperCase());
        if (idx <= 0) return Optional.empty(); // R32 has no previous knockout round
        String prevStage = ORDER.get(idx - 1);
        return findById(prevStage);
    }
}
