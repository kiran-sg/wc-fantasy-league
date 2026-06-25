package com.wc.fantasy.controller;

import com.wc.fantasy.model.Player;
import com.wc.fantasy.repository.MatchPlayerStatsRepository;
import com.wc.fantasy.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/players")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PlayerController {

    private final PlayerRepository playerRepo;
    private final MatchPlayerStatsRepository statsRepo;

    @GetMapping
    public List<Player> getAll() {
        return playerRepo.findAll();
    }

    @GetMapping("/team/{teamId}")
    public List<Player> getByTeam(@PathVariable Long teamId) {
        return playerRepo.findByTeamId(teamId);
    }

    @GetMapping("/points")
    public Map<Long, Integer> getPlayerPoints() {
        Map<Long, Integer> result = new HashMap<>();
        for (Object[] row : statsRepo.sumPointsAllPlayers()) {
            result.put(((Number) row[0]).longValue(), ((Number) row[1]).intValue());
        }
        return result;
    }
}
