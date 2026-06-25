package com.wc.fantasy.controller;

import com.wc.fantasy.model.AppUser;
import com.wc.fantasy.repository.*;
import com.wc.fantasy.repository.UserTransferRecordRepository;
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
    private final UserTeamMatchPointsRepository userTeamMatchPointsRepo;
    private final UserTeamRepository userTeamRepo;
    private final UserTransferRecordRepository transferRecordRepo;

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
        userTeamMatchPointsRepo.deleteAll();
        transferRecordRepo.deleteAll();
        squadRepo.deleteAll();
        userTeamRepo.deleteAll();
        playerRepo.deleteAll();
        matchRepo.deleteAll();
        teamRepo.deleteAll();
        userRepo.findAll().forEach(u -> { u.setTotalPoints(0); userRepo.save(u); });
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

        // Clear existing squad for this match
        squadRepo.findByUserIdAndMatchId(user.getId(), match.getId()).ifPresent(squadRepo::delete);

        var eng = playerRepo.findByTeamId(match.getTeamA().getId());
        var cro = playerRepo.findByTeamId(match.getTeamB().getId());

        // Pick by position: 1 GK, 4 DEF, 3 MID, 3 FWD
        var players = new java.util.ArrayList<com.wc.fantasy.model.Player>();
        players.addAll(eng.stream().filter(p -> "GK".equals(p.getPosition())).limit(1).toList());
        players.addAll(eng.stream().filter(p -> "DEF".equals(p.getPosition())).limit(3).toList());
        players.addAll(cro.stream().filter(p -> "DEF".equals(p.getPosition())).limit(1).toList());
        players.addAll(eng.stream().filter(p -> "MID".equals(p.getPosition())).limit(2).toList());
        players.addAll(cro.stream().filter(p -> "MID".equals(p.getPosition())).limit(1).toList());
        players.addAll(eng.stream().filter(p -> "FWD".equals(p.getPosition())).limit(2).toList());
        players.addAll(cro.stream().filter(p -> "FWD".equals(p.getPosition())).limit(1).toList());

        var captain = eng.stream().filter(p -> p.getName().contains("Kane")).findFirst().orElse(players.get(0));

        var squad = new com.wc.fantasy.model.UserSquad();
        squad.setUser(user);
        squad.setMatch(match);
        squad.setPlayers(players);
        squad.setCaptain(captain);
        squad.setPointsEarned(0);
        squad.setLocked(true);
        squadRepo.save(squad);

        return Map.of("status", "squad created", "matchId", match.getId(), "captain", captain.getName(),
                "players", players.stream().map(p -> p.getName() + " (" + p.getPosition() + ")").toList());
    }
}
