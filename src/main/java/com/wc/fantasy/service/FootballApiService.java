package com.wc.fantasy.service;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class FootballApiService {

    private final TeamRepository teamRepo;
    private final PlayerRepository playerRepo;
    private final MatchRepository matchRepo;
    private final MatchPlayerStatsRepository statsRepo;

    @Value("${football-api.key:}")
    private String apiKey;

    @Value("${football-api.host:v3.football.api-sports.io}")
    private String apiHost;

    @Value("${football-api.league-id:1}")
    private int leagueId; // 1 = World Cup

    @Value("${football-api.season:2026}")
    private int season;

    private WebClient client() {
        return WebClient.builder()
                .baseUrl("https://" + apiHost)
                .defaultHeader("x-apisports-key", apiKey)
                .build();
    }

    public void syncTeams() {
        if (apiKey.isBlank()) { log.warn("API key not set, skipping team sync"); return; }
        var resp = client().get()
                .uri("/teams?league={league}&season={season}", leagueId, season)
                .retrieve().bodyToMono(Map.class).block();
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("response");
        for (var item : items) {
            Map<String, Object> teamData = (Map<String, Object>) item.get("team");
            Team team = new Team();
            team.setName((String) teamData.get("name"));
            team.setCode((String) teamData.get("code"));
            team.setFlagUrl((String) teamData.get("logo"));
            teamRepo.save(team);
        }
        log.info("Synced {} teams", items.size());
    }

    public void syncPlayers(Long teamId, int apiTeamId) {
        if (apiKey.isBlank()) return;
        var resp = client().get()
                .uri("/players/squads?team={team}", apiTeamId)
                .retrieve().bodyToMono(Map.class).block();
        List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("response");
        if (items == null || items.isEmpty()) return;
        List<Map<String, Object>> players = (List<Map<String, Object>>) items.get(0).get("players");
        Team team = teamRepo.findById(teamId).orElseThrow();
        for (var p : players) {
            Player player = new Player();
            player.setName((String) p.get("name"));
            player.setPosition(mapPosition((String) p.get("position")));
            player.setJerseyNumber((Integer) p.get("number"));
            player.setPhotoUrl((String) p.get("photo"));
            player.setTeam(team);
            playerRepo.save(player);
        }
        log.info("Synced {} players for team {}", players.size(), team.getName());
    }

    @Scheduled(fixedDelay = 3600000) // every hour
    public void syncMatchStats() {
        if (apiKey.isBlank()) return;
        List<Match> liveMatches = matchRepo.findByStatusOrderByMatchTimeAsc("COMPLETED");
        for (Match match : liveMatches) {
            // Would fetch from /fixtures/statistics and /fixtures/players
            log.debug("Checking stats for match {}", match.getId());
        }
    }

    private String mapPosition(String pos) {
        if (pos == null) return "MID";
        return switch (pos) {
            case "Goalkeeper" -> "GK";
            case "Defender" -> "DEF";
            case "Midfielder" -> "MID";
            case "Attacker" -> "FWD";
            default -> "MID";
        };
    }
}
