package com.wc.fantasy.config;

import com.wc.fantasy.model.Match;
import com.wc.fantasy.repository.MatchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class MatchStatusScheduler {

    private final MatchRepository matchRepo;

    // Runs every 60 seconds
    @Scheduled(fixedDelay = 60_000)
    public void updateMatchStatuses() {
        // matchTime is stored as IST — compare in IST so Railway (UTC) doesn't lag by 5h30m
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Kolkata"));
        List<Match> matches = matchRepo.findAll();

        for (Match match : matches) {
            String current = match.getStatus();
            LocalDateTime start = match.getMatchTime();
            LocalDateTime end = start.plusMinutes(120); // 90 min + extra time buffer

            if ("UPCOMING".equals(current) && !now.isBefore(start)) {
                match.setStatus("LIVE");
                matchRepo.save(match);
                log.info("Match {} vs {} set to LIVE", match.getTeamA().getName(), match.getTeamB().getName());
            } else if ("LIVE".equals(current) && now.isAfter(end)) {
                match.setStatus("COMPLETED");
                matchRepo.save(match);
                log.info("Match {} vs {} set to COMPLETED", match.getTeamA().getName(), match.getTeamB().getName());
            }
        }
    }
}
