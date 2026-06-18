package com.wc.fantasy.controller;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import com.wc.fantasy.service.EspnScraperService;
import com.wc.fantasy.service.DataSyncService;
import com.wc.fantasy.service.SquadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminController {

    private final MatchRepository matchRepo;
    private final PlayerRepository playerRepo;
    private final MatchPlayerStatsRepository statsRepo;
    private final UserSquadRepository squadRepo;
    private final SquadService squadService;
    private final EspnScraperService scraperService;
    private final DataSyncService dataSyncService;

    @PostMapping("/sync-data")
    public Map<String, Object> syncData() {
        return dataSyncService.syncAll();
    }

    @PostMapping("/update-scores/{matchId}")
    public Map<String, Object> updateScores(@PathVariable Long matchId) {
        Match match = matchRepo.findById(matchId).orElseThrow(() -> new IllegalArgumentException("Match not found"));

        EspnScraperService.ScrapedMatchResult result = scraperService.scrapeMatch(match);
        if (result == null) {
            return Map.of("status", "error", "message", "Could not scrape match data from ESPN. Match may not be finished yet.");
        }

        List<MatchPlayerStats> allStats = scraperService.buildStats(match, result);
        if (allStats.isEmpty()) {
            return Map.of("status", "error", "message", "No player stats could be built from scraped data.");
        }

        // Save all stats
        statsRepo.deleteAll(statsRepo.findByMatchId(matchId));
        statsRepo.saveAll(allStats);

        // Mark match as completed
        match.setStatus("COMPLETED");
        match.setScoreA(result.homeScore());
        match.setScoreB(result.awayScore());
        matchRepo.save(match);

        // Calculate fantasy points for all user squads
        squadService.calculatePoints(matchId);

        return Map.of(
                "status", "success",
                "matchId", matchId,
                "scoreA", match.getScoreA(),
                "scoreB", match.getScoreB(),
                "statsCount", allStats.size()
        );
    }

    @GetMapping("/match-stats/{matchId}")
    public List<MatchPlayerStats> getMatchStats(@PathVariable Long matchId) {
        return statsRepo.findByMatchId(matchId);
    }

    @GetMapping("/match-squads/{matchId}")
    public List<UserSquad> getMatchSquads(@PathVariable Long matchId) {
        return squadRepo.findByMatchId(matchId);
    }

    @GetMapping("/matches")
    public List<Match> getAllMatches() {
        return matchRepo.findAll(org.springframework.data.domain.Sort.by("matchTime"));
    }
}
