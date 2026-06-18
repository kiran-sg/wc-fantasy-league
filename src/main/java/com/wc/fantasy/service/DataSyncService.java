package com.wc.fantasy.service;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncService {

    private final TeamRepository teamRepo;
    private final MatchRepository matchRepo;
    private final PlayerRepository playerRepo;

    private static final String TEAMS_URL = "https://raw.githubusercontent.com/rezarahiminia/worldcup2026/main/football.teams.json";
    private static final String STADIUMS_URL = "https://raw.githubusercontent.com/rezarahiminia/worldcup2026/main/football.stadiums.json";
    private static final String MATCHES_URL = "https://raw.githubusercontent.com/rezarahiminia/worldcup2026/main/football.matches.json";
    private static final String ESPN_TEAMS_URL = "https://www.espn.com/soccer/teams/_/league/fifa.world";
    private static final String ESPN_SQUAD_URL = "https://www.espn.com/soccer/team/squad/_/id/";

    private static final Map<String, String> OFFICIAL_UTC = Map.ofEntries(
        Map.entry("1","2026-06-11T19:00"), Map.entry("2","2026-06-12T02:00"), Map.entry("3","2026-06-12T19:00"),
        Map.entry("4","2026-06-13T01:00"), Map.entry("5","2026-06-13T04:00"), Map.entry("6","2026-06-13T19:00"),
        Map.entry("7","2026-06-13T22:00"), Map.entry("8","2026-06-14T01:00"), Map.entry("9","2026-06-14T17:00"),
        Map.entry("10","2026-06-14T20:00"), Map.entry("11","2026-06-14T23:00"), Map.entry("12","2026-06-15T02:00"),
        Map.entry("13","2026-06-15T16:00"), Map.entry("14","2026-06-15T19:00"), Map.entry("15","2026-06-15T22:00"),
        Map.entry("16","2026-06-16T01:00"), Map.entry("17","2026-06-16T04:00"), Map.entry("18","2026-06-16T19:00"),
        Map.entry("19","2026-06-16T22:00"), Map.entry("20","2026-06-17T01:00"), Map.entry("21","2026-06-17T17:00"),
        Map.entry("22","2026-06-17T20:00"), Map.entry("23","2026-06-17T23:00"), Map.entry("24","2026-06-18T02:00"),
        Map.entry("25","2026-06-18T16:00"), Map.entry("26","2026-06-18T19:00"), Map.entry("27","2026-06-18T22:00"),
        Map.entry("28","2026-06-19T01:00"), Map.entry("29","2026-06-19T04:00"), Map.entry("30","2026-06-19T19:00"),
        Map.entry("31","2026-06-19T22:00"), Map.entry("32","2026-06-20T01:00"), Map.entry("33","2026-06-20T04:00"),
        Map.entry("34","2026-06-20T17:00"), Map.entry("35","2026-06-20T20:00"), Map.entry("36","2026-06-21T00:00"),
        Map.entry("37","2026-06-21T16:00"), Map.entry("38","2026-06-21T19:00"), Map.entry("39","2026-06-21T22:00"),
        Map.entry("40","2026-06-22T01:00"), Map.entry("41","2026-06-22T17:00"), Map.entry("42","2026-06-22T21:00"),
        Map.entry("43","2026-06-23T00:00"), Map.entry("44","2026-06-23T03:00"), Map.entry("45","2026-06-23T17:00"),
        Map.entry("46","2026-06-23T20:00"), Map.entry("47","2026-06-23T23:00"), Map.entry("48","2026-06-24T02:00"),
        Map.entry("49","2026-06-24T19:00"), Map.entry("50","2026-06-24T19:00"), Map.entry("51","2026-06-24T22:00"),
        Map.entry("52","2026-06-24T22:00"), Map.entry("53","2026-06-25T01:00"), Map.entry("54","2026-06-25T01:00"),
        Map.entry("55","2026-06-25T20:00"), Map.entry("56","2026-06-25T20:00"), Map.entry("57","2026-06-25T23:00"),
        Map.entry("58","2026-06-25T23:00"), Map.entry("59","2026-06-26T02:00"), Map.entry("60","2026-06-26T02:00"),
        Map.entry("61","2026-06-26T19:00"), Map.entry("62","2026-06-26T19:00"), Map.entry("63","2026-06-27T00:00"),
        Map.entry("64","2026-06-27T00:00"), Map.entry("65","2026-06-27T03:00"), Map.entry("66","2026-06-27T03:00"),
        Map.entry("67","2026-06-27T21:00"), Map.entry("68","2026-06-27T21:00"), Map.entry("69","2026-06-27T23:30"),
        Map.entry("70","2026-06-27T23:30"), Map.entry("71","2026-06-28T02:00"), Map.entry("72","2026-06-28T02:00")
    );

    private static final Map<String, String> GROUP_MAP = Map.ofEntries(
        Map.entry("1","A"), Map.entry("2","B"), Map.entry("3","C"), Map.entry("4","D"),
        Map.entry("5","E"), Map.entry("6","F"), Map.entry("7","G"), Map.entry("8","H"),
        Map.entry("9","I"), Map.entry("10","J"), Map.entry("11","K"), Map.entry("12","L")
    );

    private static final Map<String, String> ESPN_NAME_ALIASES = Map.of(
        "congo dr", "Democratic Republic of the Congo",
        "curacao", "Curaçao",
        "czechia", "Czech Republic",
        "bosnia herzegovina", "Bosnia and Herzegovina",
        "turkiye", "Turkey"
    );

    private static final Map<String, String> POS_MAP = Map.of("G", "GK", "D", "DEF", "M", "MID", "F", "FWD");

    private WebClient webClient() {
        return WebClient.builder()
                .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .defaultHeader("Accept", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                .build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fetchList(String url) {
        try {
            String json = webClient().get().uri(url).retrieve().bodyToMono(String.class).block();
            if (json == null) return null;
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, List.class);
        } catch (Exception e) {
            log.error("Failed to fetch {}: {}", url, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> syncAll() {
        int teams = syncTeams();
        int matches = syncMatches();
        int players = syncPlayers();
        return Map.of("teams", teams, "matches", matches, "players", players);
    }

    @SuppressWarnings("unchecked")
    public int syncTeams() {
        List<Map<String, Object>> teams = fetchList(TEAMS_URL);
        if (teams == null) return 0;

        int count = 0;
        for (Map<String, Object> t : teams) {
            String code = (String) t.get("fifa_code");
            String name = (String) t.get("name_en");
            String flag = (String) t.get("flag");
            Object groupId = t.get("group_id");
            String group = groupId != null ? GROUP_MAP.get(String.valueOf(((Number) groupId).intValue())) : null;

            Team existing = teamRepo.findAll().stream().filter(x -> x.getCode() != null && x.getCode().equals(code)).findFirst().orElse(null);
            if (existing == null) {
                Team team = new Team();
                team.setName(name);
                team.setCode(code);
                team.setGroup(group);
                team.setFlagUrl(flag);
                teamRepo.save(team);
                count++;
            } else {
                existing.setName(name);
                existing.setGroup(group);
                existing.setFlagUrl(flag);
                teamRepo.save(existing);
            }
        }
        log.info("Synced {} teams", count);
        return teamRepo.findAll().size();
    }

    @SuppressWarnings("unchecked")
    public int syncMatches() {
        List<Map<String, Object>> teamsData = fetchList(TEAMS_URL);
        List<Map<String, Object>> stadiums = fetchList(STADIUMS_URL);
        List<Map<String, Object>> matches = fetchList(MATCHES_URL);
        if (teamsData == null || stadiums == null || matches == null) return 0;

        Map<String, String> teamIdToCode = new HashMap<>();
        for (Map<String, Object> t : teamsData) teamIdToCode.put(String.valueOf(t.get("id")), (String) t.get("fifa_code"));
        Map<String, String> stadiumMap = new HashMap<>();
        for (Map<String, Object> s : stadiums) stadiumMap.put(String.valueOf(s.get("id")), (String) s.get("name_en"));

        Map<String, Team> codeToTeam = new HashMap<>();
        teamRepo.findAll().forEach(t -> { if (t.getCode() != null) codeToTeam.put(t.getCode(), t); });

        if (matchRepo.count() > 0) {
            matchRepo.deleteAll();
        }

        int count = 0;
        for (Map<String, Object> m : matches) {
            String matchId = String.valueOf(m.get("id"));
            String homeCode = teamIdToCode.get(String.valueOf(m.get("home_team_id")));
            String awayCode = teamIdToCode.get(String.valueOf(m.get("away_team_id")));
            Team teamA = codeToTeam.get(homeCode);
            Team teamB = codeToTeam.get(awayCode);
            if (teamA == null || teamB == null) continue;

            String utcStr = OFFICIAL_UTC.get(matchId);
            LocalDateTime matchTime = utcStr != null
                    ? LocalDateTime.parse(utcStr, DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm"))
                    : LocalDateTime.now().plusDays(1);

            Match match = new Match();
            match.setTeamA(teamA);
            match.setTeamB(teamB);
            match.setMatchTime(matchTime);
            match.setVenue(stadiumMap.getOrDefault(String.valueOf(m.get("stadium_id")), "TBD"));
            match.setStage("GROUP");
            match.setStatus("UPCOMING");
            matchRepo.save(match);
            count++;
        }
        log.info("Synced {} matches", count);
        return count;
    }

    public int syncPlayers() {
        playerRepo.deleteAll();

        // Get ESPN team IDs from teams page
        String html = webClient().get().uri(ESPN_TEAMS_URL).retrieve().bodyToMono(String.class).block();
        if (html == null) return 0;

        Map<String, String> espnTeams = new LinkedHashMap<>();
        Matcher m = Pattern.compile("/soccer/team/_/id/(\\d+)/([a-z-]+)").matcher(html);
        while (m.find()) espnTeams.putIfAbsent(m.group(1), m.group(2).replace("-", " "));

        Map<String, Team> nameToTeam = new HashMap<>();
        teamRepo.findAll().forEach(t -> nameToTeam.put(t.getName().toLowerCase(), t));

        int total = 0;
        for (Map.Entry<String, String> entry : espnTeams.entrySet()) {
            String espnId = entry.getKey();
            String espnName = entry.getValue();

            Team team = findTeamByEspnName(espnName, nameToTeam);
            if (team == null) continue;

            List<String[]> players = fetchSquad(espnId);
            for (String[] p : players) {
                Player player = new Player();
                player.setName(p[0]);
                player.setPosition(p[1]);
                player.setTeam(team);
                playerRepo.save(player);
                total++;
            }
        }
        log.info("Synced {} players from ESPN", total);
        return total;
    }

    private List<String[]> fetchSquad(String espnTeamId) {
        try {
            String html = webClient().get().uri(ESPN_SQUAD_URL + espnTeamId)
                    .retrieve().bodyToMono(String.class).block();
            if (html == null) return List.of();

            List<String[]> players = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            Matcher m = Pattern.compile("\"name\":\"([^\"]+)\",\"href\":\"https://www\\.espn\\.com/soccer/player/_/id/(\\d+)/[^\"]*\"[^}]*\"position\":\"([GDMF])\"").matcher(html);
            while (m.find()) {
                String name = m.group(1);
                String pid = m.group(2);
                String pos = POS_MAP.getOrDefault(m.group(3), "MID");
                if (seen.add(pid)) players.add(new String[]{name, pos});
            }
            return players;
        } catch (Exception e) {
            log.warn("Failed to fetch squad for ESPN team {}: {}", espnTeamId, e.getMessage());
            return List.of();
        }
    }

    private Team findTeamByEspnName(String espnName, Map<String, Team> nameToTeam) {
        String lower = espnName.toLowerCase().trim();
        if (nameToTeam.containsKey(lower)) return nameToTeam.get(lower);
        for (Map.Entry<String, Team> e : nameToTeam.entrySet()) {
            if (e.getKey().contains(lower) || lower.contains(e.getKey())) return e.getValue();
        }
        String alias = ESPN_NAME_ALIASES.get(lower);
        if (alias != null) return nameToTeam.get(alias.toLowerCase());
        return null;
    }
}
