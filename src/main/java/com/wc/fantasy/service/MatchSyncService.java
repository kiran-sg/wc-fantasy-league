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
            LocalDateTime matchTime = parseDateTime((String) m.get("dateTime"));

            // Primary dedup: by matchNo marker in venue
            Match existing = matchRepo.findAll().stream()
                    .filter(ex -> ex.getVenue() != null && ex.getVenue().contains("[#" + matchNo + "]"))
                    .findFirst().orElse(null);

            // Fallback dedup: same two teams on the same day — compare by normalized name to handle
            // accent variants (e.g. "México" vs "Mexico") and reversed team order across syncs
            if (existing == null && matchTime != null) {
                String normA = normalizeTeamName(teamAName);
                String normB = normalizeTeamName(teamBName);
                existing = matchRepo.findAll().stream()
                        .filter(ex -> ex.getTeamA() != null && ex.getTeamB() != null
                                && ex.getMatchTime() != null
                                && ex.getMatchTime().toLocalDate().equals(matchTime.toLocalDate())
                                && ((normalizeTeamName(ex.getTeamA().getName()).equals(normA)
                                        && normalizeTeamName(ex.getTeamB().getName()).equals(normB))
                                    || (normalizeTeamName(ex.getTeamA().getName()).equals(normB)
                                        && normalizeTeamName(ex.getTeamB().getName()).equals(normA))))
                        .findFirst().orElse(null);
            }

            if (existing == null) {
                Match match = new Match();
                match.setTeamA(teamA);
                match.setTeamB(teamB);
                match.setMatchTime(matchTime);
                match.setVenue(m.get("venue") + " [#" + matchNo + "]");
                match.setStage(resolveStageFromPayload(m, matchNo));
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
        String normalizedName = normalizeTeamName(name);
        return teamRepo.findAll().stream()
                .filter(t -> normalizeTeamName(t.getName()).equals(normalizedName))
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

    private String normalizeTeamName(String name) {
        if (name == null) return "";
        String s = name.toLowerCase().trim()
                .replaceAll("[áàâãä]", "a").replaceAll("[éèêë]", "e")
                .replaceAll("[íìîï]", "i").replaceAll("[óòôõö]", "o")
                .replaceAll("[úùûü]", "u").replaceAll("[ñ]", "n");
        s = s.replace("czechia", "czech republic")
             .replace("turkiye", "turkey").replace("türkiye", "turkey")
             .replace("bosnia-herzegovina", "bosnia and herzegovina")
             .replace("bosnia herzegovina", "bosnia and herzegovina")
             .replace("cote d'ivoire", "ivory coast").replace("cote divoire", "ivory coast");
        if (s.contains("korea") && !s.contains("south")) s = "south korea";
        if (s.contains("united states") || s.equals("usa")) s = "united states";
        return s;
    }

    // Try to read stage/type from prediction API payload, fall back to match-number range.
    private String resolveStageFromPayload(Map<String, Object> m, String matchNo) {
        for (String key : List.of("stage", "type", "round")) {
            Object val = m.get(key);
            if (val instanceof String s && !s.isBlank()) {
                switch (s.toLowerCase().trim()) {
                    case "group":  return "GROUP";
                    case "r32":    return "R32";
                    case "r16":    return "R16";
                    case "qf": case "quarterfinal": case "quarter-final": return "QF";
                    case "sf": case "semifinal":    case "semi-final":    return "SF";
                    case "third":  return "SF";
                    case "final":  return "FINAL";
                }
                String u = s.toUpperCase().trim();
                if (u.equals("GROUP") || u.equals("R32") || u.equals("R16") || u.equals("QF") || u.equals("SF") || u.equals("FINAL")) return u;
            }
        }
        // Fall back to match-number range (72 group + 16 r32 + 8 r16 + 4 qf + 2 sf + 1 final)
        try {
            int id = Integer.parseInt(matchNo);
            if (id <= 72) return "GROUP";
            if (id <= 88) return "R32";
            if (id <= 96) return "R16";
            if (id <= 100) return "QF";
            if (id <= 102) return "SF";
            return "FINAL";
        } catch (NumberFormatException e) {
            return "GROUP";
        }
    }

    private LocalDateTime parseDateTime(String dt) {
        try {
            return OffsetDateTime.parse(dt).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now().plusDays(1);
        }
    }
}
