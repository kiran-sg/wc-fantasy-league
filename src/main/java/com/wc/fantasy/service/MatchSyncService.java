package com.wc.fantasy.service;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class MatchSyncService {

    private final MatchRepository matchRepo;
    private final TeamRepository teamRepo;
    private final PlayerRepository playerRepo;

    @Value("${prediction-api.url:http://localhost:8082}")
    private String predictionApiUrl;

    private WebClient client() {
        return WebClient.create(predictionApiUrl);
    }

    public void syncMatchesFromPredictionApi() {
        List<Map<String, Object>> matches = client().get()
                .uri("/api/matches")
                .retrieve()
                .bodyToMono(List.class)
                .block();

        if (matches == null) return;
        int synced = 0;
        Set<String> syncedTeams = new HashSet<>();

        for (Map<String, Object> m : matches) {
            String teamAName = (String) m.get("teamA");
            String teamBName = (String) m.get("teamB");

            Team teamA = getOrCreateTeam(teamAName, (String) m.get("teamALogo"), (String) m.get("groupName"));
            Team teamB = getOrCreateTeam(teamBName, (String) m.get("teamBLogo"), (String) m.get("groupName"));

            // Sync players for each team once
            if (syncedTeams.add(teamAName)) syncPlayersForTeam(teamA);
            if (syncedTeams.add(teamBName)) syncPlayersForTeam(teamB);

            String matchNo = String.valueOf(m.get("matchNo"));
            Match existing = matchRepo.findAll().stream()
                    .filter(ex -> ex.getVenue() != null && ex.getVenue().contains("[#" + matchNo + "]"))
                    .findFirst().orElse(null);

            if (existing == null) {
                Match match = new Match();
                match.setTeamA(teamA);
                match.setTeamB(teamB);
                match.setMatchTime(parseDateTime((String) m.get("dateTime")));
                match.setVenue(m.get("venue") + " [#" + matchNo + "]");
                match.setStage("GROUP");
                match.setStatus("UPCOMING");
                matchRepo.save(match);
                synced++;
            }
        }
        log.info("Synced {} matches from prediction API", synced);
    }

    public void syncPlayersForTeam(Team team) {
        if (playerRepo.findByTeamId(team.getId()).size() > 0) return;

        try {
            List<Map<String, Object>> players = client().get()
                    .uri(uriBuilder -> uriBuilder.path("/api/players/team").queryParam("team", team.getName()).build())
                    .retrieve()
                    .bodyToMono(List.class)
                    .block();

            if (players == null) return;
            for (Map<String, Object> p : players) {
                Player player = new Player();
                player.setName((String) p.get("playerName"));
                player.setPosition((String) p.get("position"));
                player.setTeam(team);
                playerRepo.save(player);
            }
            log.info("Synced {} players for {}", players.size(), team.getName());
        } catch (Exception e) {
            log.warn("Failed to sync players for {}: {}", team.getName(), e.getMessage());
        }
    }

    private Team getOrCreateTeam(String name, String logoUrl, String group) {
        return teamRepo.findAll().stream()
                .filter(t -> t.getName().equals(name))
                .findFirst()
                .orElseGet(() -> {
                    Team t = new Team();
                    t.setName(name);
                    t.setCode(name.length() >= 3 ? name.substring(0, 3).toUpperCase() : name.toUpperCase());
                    t.setGroup(group);
                    t.setFlagUrl(logoUrl);
                    return teamRepo.save(t);
                });
    }

    private LocalDateTime parseDateTime(String dt) {
        try {
            return OffsetDateTime.parse(dt).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now().plusDays(1);
        }
    }
}
