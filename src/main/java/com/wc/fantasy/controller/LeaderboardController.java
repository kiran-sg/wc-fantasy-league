package com.wc.fantasy.controller;

import com.wc.fantasy.model.AppUser;
import com.wc.fantasy.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    @GetMapping
    public List<AppUser> getOverallLeaderboard() {
        return leaderboardService.getOverallLeaderboard();
    }

    @GetMapping("/round/{matchId}")
    public List<Map<String, Object>> getRoundLeaderboard(@PathVariable Long matchId) {
        return leaderboardService.getRoundLeaderboard(matchId);
    }
}
