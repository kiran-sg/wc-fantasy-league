package com.wc.fantasy.controller;

import com.wc.fantasy.model.AppUser;
import com.wc.fantasy.service.LeaderboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leaderboard")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class LeaderboardController {

    private final LeaderboardService leaderboardService;

    @GetMapping
    public List<AppUser> getLeaderboard() {
        return leaderboardService.getLeaderboard();
    }
}
