package com.wc.fantasy.controller;

import com.wc.fantasy.model.AppUser;
import com.wc.fantasy.repository.*;
import com.wc.fantasy.service.DataSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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
    private final UserRepository userRepo;

    @PostMapping("/users")
    public Map<String, Object> addUsers(@RequestBody List<Map<String, String>> users) {
        int count = 0;
        for (Map<String, String> u : users) {
            String username = u.get("username");
            if (username != null && userRepo.findByUsername(username).isEmpty()) {
                AppUser user = new AppUser();
                user.setUsername(username);
                user.setDisplayName(u.getOrDefault("displayName", username));
                userRepo.save(user);
                count++;
            }
        }
        return Map.of("added", count);
    }

    @GetMapping("/reset")
    public Map<String, Object> reset() {
        statsRepo.deleteAll();
        squadRepo.deleteAll();
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

    @GetMapping("/seed-squad")
    public Map<String, Object> seedSquad() {
        AppUser user = userRepo.findByUsername("player1").orElse(null);
        if (user == null) return Map.of("error", "player1 not found");

        var match = matchRepo.findAll().stream()
                .filter(m -> m.getTeamA().getName().equals("England") && m.getTeamB().getName().equals("Croatia"))
                .findFirst().orElse(null);
        if (match == null) return Map.of("error", "England vs Croatia not found");

        var eng = playerRepo.findByTeamId(match.getTeamA().getId());
        var cro = playerRepo.findByTeamId(match.getTeamB().getId());
        if (eng.size() < 8 || cro.size() < 3) return Map.of("error", "not enough players");

        var squad = new com.wc.fantasy.model.UserSquad();
        squad.setUser(user);
        squad.setMatch(match);
        var players = new java.util.ArrayList<>(eng.subList(0, 8));
        players.addAll(cro.subList(0, 3));
        squad.setPlayers(players);
        squad.setCaptain(eng.stream().filter(p -> p.getName().contains("Kane")).findFirst().orElse(eng.get(0)));
        squad.setPointsEarned(0);
        squad.setLocked(true);
        squadRepo.save(squad);

        return Map.of("status", "squad created", "matchId", match.getId(), "captain", squad.getCaptain().getName(), "players", players.size());
    }
}
