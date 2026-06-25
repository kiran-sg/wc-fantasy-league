package com.wc.fantasy.controller;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import com.wc.fantasy.service.EspnScraperService;
import com.wc.fantasy.service.FifaScraperService;
import com.wc.fantasy.service.SquadService;
import com.wc.fantasy.service.UserTeamService;
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
    private final MatchPlayerStatsRepository statsRepo;
    private final UserSquadRepository squadRepo;
    private final SquadService squadService;
    private final EspnScraperService scraperService;
    private final UserTeamService userTeamService;
    private final FifaScraperService fifaScraperService;
    private final com.wc.fantasy.repository.UserRepository userRepo;

    // ── User management ──────────────────────────────────────────────────────

    @GetMapping("/users")
    public List<com.wc.fantasy.model.AppUser> listUsers() {
        return userRepo.findAll();
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username == null || username.isBlank())
            return Map.of("status", "error", "message", "Username is required");
        if (userRepo.findByUsername(username).isPresent())
            return Map.of("status", "error", "message", "Username already exists");
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(body.getOrDefault("displayName", username));
        user.setLocation(body.get("location"));
        user.setIsAdmin(Boolean.parseBoolean(body.getOrDefault("isAdmin", "false")));
        userRepo.save(user);
        return Map.of("status", "success", "userId", user.getId());
    }

    @PostMapping("/update-scores/{matchId}")
    public Map<String, Object> updateScores(@PathVariable Long matchId) {
        Match match = matchRepo.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Match not found"));

        List<MatchPlayerStats> stats = scraperService.fetchAndBuildStats(match);
        if (stats.isEmpty()) {
            return Map.of("status", "error", "message", "Could not fetch match data from ESPN. Match may not be finished yet.");
        }

        // Update match score from ESPN
        EspnScraperService.ScoreResult score = scraperService.fetchScore(match);
        if (score != null) {
            match.setScoreA(score.homeScore());
            match.setScoreB(score.awayScore());
        }
        match.setStatus("COMPLETED");
        matchRepo.save(match);

        statsRepo.deleteAll(statsRepo.findByMatchId(matchId));
        statsRepo.saveAll(stats);

        // Calculate points for old per-match squads (UserSquad model, kept for backwards compat)
        squadService.calculatePoints(matchId);

        // Calculate points for persistent user teams (new model)
        userTeamService.calculatePointsForMatch(matchId, match);

        return Map.of(
                "status", "success",
                "matchId", matchId,
                "scoreA", match.getScoreA() != null ? match.getScoreA() : 0,
                "scoreB", match.getScoreB() != null ? match.getScoreB() : 0,
                "statsCount", stats.size()
        );
    }

    @PostMapping("/sync-fifa-prices")
    public Map<String, Object> syncFifaPrices() {
        try {
            FifaScraperService.SyncResult result = fifaScraperService.syncPrices();
            return Map.of(
                    "status", "success",
                    "matched", result.matched(),
                    "unmatched", result.unmatched(),
                    "unmatchedNames", result.unmatchedNames()
            );
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
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
