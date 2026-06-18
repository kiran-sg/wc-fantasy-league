package com.wc.fantasy.controller;

import com.wc.fantasy.repository.*;
import com.wc.fantasy.service.DataSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sync")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SyncController {

    private final DataSyncService dataSyncService;
    private final MatchPlayerStatsRepository statsRepo;
    private final UserSquadRepository squadRepo;
    private final PlayerRepository playerRepo;
    private final MatchRepository matchRepo;
    private final TeamRepository teamRepo;

    @GetMapping("/reset")
    public Map<String, Object> reset() {
        playerRepo.deleteAll();
        matchRepo.deleteAll();
        teamRepo.deleteAll();
        return Map.of("status", "reset complete");
    }

    @GetMapping("/all")
    public Map<String, Object> syncAll() {
        return dataSyncService.syncAll();
    }

    @GetMapping("/teams")
    public Map<String, Object> syncTeams() {
        return Map.of("teams", dataSyncService.syncTeams());
    }

    @GetMapping("/matches")
    public Map<String, Object> syncMatches() {
        return Map.of("matches", dataSyncService.syncMatches());
    }

    @GetMapping("/players")
    public Map<String, Object> syncPlayers() {
        return Map.of("players", dataSyncService.syncPlayers());
    }
}
