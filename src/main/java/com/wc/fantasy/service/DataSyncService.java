package com.wc.fantasy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final FifaScraperService fifaScraperService;
    private final RoundConfigRepository roundConfigRepo;
    private final UserSquadRepository userSquadRepo;
    private final MatchPlayerStatsRepository matchStatsRepo;
    private final UserTeamMatchPointsRepository userTeamMatchPointsRepo;

    private static final String TEAMS_URL = "https://raw.githubusercontent.com/rezarahiminia/worldcup2026/main/football.teams.json";
    private static final String STADIUMS_URL = "https://raw.githubusercontent.com/rezarahiminia/worldcup2026/main/football.stadiums.json";
    private static final String MATCHES_URL = "https://raw.githubusercontent.com/rezarahiminia/worldcup2026/main/football.matches.json";
    private static final String ESPN_TEAMS_URL = "https://www.espn.com/soccer/teams/_/league/fifa.world";
    private static final String ESPN_SQUAD_URL  = "https://www.espn.com/soccer/team/squad/_/id/";
    private static final String ESPN_SCOREBOARD = "https://site.api.espn.com/apis/site/v2/sports/soccer/fifa.world/scoreboard?dates=";

    // Dates covering R32 through Final
    private static final List<String> KNOCKOUT_DATES = List.of(
        "20260628","20260629","20260630","20260701","20260702","20260703","20260704",
        "20260705","20260706","20260707","20260708","20260709",
        "20260711","20260712","20260713",
        "20260715","20260716",
        "20260718","20260719"
    );

    // All match dates: group stage (Jun 11-27) + knockout (Jun 28-Jul 19)
    private static final List<String> ALL_MATCH_DATES = List.of(
        "20260611","20260612","20260613","20260614","20260615","20260616","20260617",
        "20260618","20260619","20260620","20260621","20260622","20260623","20260624",
        "20260625","20260626","20260627",
        "20260628","20260629","20260630","20260701","20260702","20260703","20260704",
        "20260705","20260706","20260707","20260708","20260709",
        "20260711","20260712","20260713",
        "20260715","20260716",
        "20260718","20260719"
    );

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
        Map.entry("70","2026-06-27T23:30"), Map.entry("71","2026-06-28T02:00"), Map.entry("72","2026-06-28T02:00"),
        // R32 (IDs 73-88)
        Map.entry("73","2026-06-28T16:00"), Map.entry("74","2026-06-29T20:30"),
        Map.entry("75","2026-06-29T23:00"), Map.entry("76","2026-06-29T16:00"),
        Map.entry("77","2026-06-30T21:00"), Map.entry("78","2026-06-30T16:00"),
        Map.entry("79","2026-06-30T23:00"), Map.entry("80","2026-07-01T16:00"),
        Map.entry("81","2026-07-01T21:00"), Map.entry("82","2026-07-01T17:00"),
        Map.entry("83","2026-07-02T23:00"), Map.entry("84","2026-07-02T16:00"),
        Map.entry("85","2026-07-03T00:00"), Map.entry("86","2026-07-03T22:00"),
        Map.entry("87","2026-07-04T00:30"), Map.entry("88","2026-07-03T17:00"),
        // R16 (IDs 89-96)
        Map.entry("89","2026-07-05T20:00"), Map.entry("90","2026-07-06T00:00"),
        Map.entry("91","2026-07-06T20:00"), Map.entry("92","2026-07-07T00:00"),
        Map.entry("93","2026-07-07T20:00"), Map.entry("94","2026-07-08T00:00"),
        Map.entry("95","2026-07-08T20:00"), Map.entry("96","2026-07-09T00:00"),
        // QF (IDs 97-100)
        Map.entry("97","2026-07-11T20:00"), Map.entry("98","2026-07-12T00:00"),
        Map.entry("99","2026-07-12T20:00"), Map.entry("100","2026-07-13T00:00"),
        // SF (IDs 101-102)
        Map.entry("101","2026-07-15T00:00"), Map.entry("102","2026-07-16T00:00"),
        // 3rd place + Final (IDs 103-104)
        Map.entry("103","2026-07-18T20:00"), Map.entry("104","2026-07-19T20:00")
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

    // Called after any match sync — fills roundStart for rows where it is still null
    public void refreshRoundStarts() {
        for (RoundConfig rc : roundConfigRepo.findAll()) {
            if (rc.getRoundStart() != null) continue; // preserve manual edits
            matchRepo.findAll().stream()
                    .filter(m -> rc.getStage().equalsIgnoreCase(m.getStage()) && m.getMatchTime() != null)
                    .map(com.wc.fantasy.model.Match::getMatchTime)
                    .min(java.time.LocalDateTime::compareTo)
                    .ifPresent(earliest -> {
                        rc.setRoundStart(earliest);
                        roundConfigRepo.save(rc);
                        log.info("refreshRoundStarts: stage={} roundStart={}", rc.getStage(), earliest);
                    });
        }
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

    public int syncMatches() {
        ObjectMapper mapper = new ObjectMapper();
        ZoneId ist = ZoneId.of("Asia/Kolkata");

        // Build team lookup by normalised name
        Map<String, Team> nameToTeam = new HashMap<>();
        teamRepo.findAll().forEach(t -> nameToTeam.put(normalizeTeamName(t.getName()), t));

        // Collect match IDs referenced in dependent tables — these must be updated in-place, not deleted
        Set<Long> referencedIds = new HashSet<>();
        userTeamMatchPointsRepo.findAll().forEach(p -> { if (p.getMatch() != null) referencedIds.add(p.getMatch().getId()); });
        matchStatsRepo.findAll().forEach(s -> { if (s.getMatch() != null) referencedIds.add(s.getMatch().getId()); });
        userSquadRepo.findAll().forEach(s -> { if (s.getMatch() != null) referencedIds.add(s.getMatch().getId()); });

        // Delete only unreferenced matches so referenced ones can be updated in-place
        List<Long> unreferencedIds = matchRepo.findAll().stream()
                .map(Match::getId).filter(id -> !referencedIds.contains(id)).toList();
        if (!unreferencedIds.isEmpty()) matchRepo.deleteAllById(unreferencedIds);
        log.info("Deleted {} unreferenced matches, keeping {} referenced", unreferencedIds.size(), referencedIds.size());

        // Index remaining (referenced) matches by ESPN stamp and by matchTime for dedup
        Map<String, Match> existingByEspnId = new HashMap<>();
        Map<LocalDateTime, Match> existingByTime = new HashMap<>();
        matchRepo.findAll().forEach(ex -> {
            if (ex.getVenue() != null) {
                java.util.regex.Matcher vm = java.util.regex.Pattern.compile("\\[#espn(\\w+)\\]").matcher(ex.getVenue());
                if (vm.find()) existingByEspnId.put(vm.group(1), ex);
            }
            if (ex.getMatchTime() != null) existingByTime.putIfAbsent(ex.getMatchTime(), ex);
        });

        int inserted = 0, updated = 0, dateErrors = 0;

        for (String date : ALL_MATCH_DATES) {
            try {
                String json = webClient().get().uri(ESPN_SCOREBOARD + date)
                        .retrieve().bodyToMono(String.class).block();
                if (json == null) continue;

                for (JsonNode event : mapper.readTree(json).path("events")) {
                    JsonNode comp = event.path("competitions").path(0);
                    String dateStr = comp.path("date").asText("");
                    if (dateStr.isBlank()) continue;

                    LocalDateTime matchTime;
                    try {
                        matchTime = java.time.OffsetDateTime.parse(dateStr)
                                .atZoneSameInstant(ist).toLocalDateTime();
                    } catch (Exception e) { continue; }

                    String homeTeamName = null, awayTeamName = null;
                    for (JsonNode c : comp.path("competitors")) {
                        String tname = c.path("team").path("displayName").asText("");
                        if (c.path("homeAway").asText().equals("home")) homeTeamName = tname;
                        else awayTeamName = tname;
                    }
                    if (homeTeamName == null || awayTeamName == null) continue;

                    Team teamA = nameToTeam.get(normalizeTeamName(homeTeamName));
                    Team teamB = nameToTeam.get(normalizeTeamName(awayTeamName));
                    if (teamA == null) log.warn("Team not found in DB: {}", homeTeamName);
                    if (teamB == null) log.warn("Team not found in DB: {}", awayTeamName);

                    String seasonSlug = event.path("season").path("slug").asText("").toLowerCase();
                    String espnNote   = comp.path("notes").path(0).path("headline").asText("").toLowerCase();
                    String stage      = resolveStageFromSlugOrNote(seasonSlug, espnNote, matchTime);
                    String venue      = comp.path("venue").path("fullName").asText("TBD");
                    String espnId     = event.path("id").asText("");

                    String espnState = comp.path("status").path("type").path("name").asText("STATUS_SCHEDULED");
                    String status;
                    if (espnState.contains("IN_PROGRESS") || espnState.contains("HALFTIME")) status = "LIVE";
                    else if (espnState.contains("FINAL") || espnState.contains("FULL_TIME")) status = "COMPLETED";
                    else status = "UPCOMING";

                    Integer scoreA = null, scoreB = null;
                    if ("COMPLETED".equals(status) || "LIVE".equals(status)) {
                        for (JsonNode c : comp.path("competitors")) {
                            int sc = c.path("score").asInt(-1);
                            if (sc >= 0) {
                                if (c.path("homeAway").asText().equals("home")) scoreA = sc;
                                else scoreB = sc;
                            }
                        }
                    }

                    // Check if this ESPN event matches an existing referenced row
                    Match existing = existingByEspnId.get(espnId);
                    if (existing == null) existing = existingByTime.get(matchTime);

                    if (existing != null) {
                        // Update in-place — preserve FK references
                        existing.setTeamA(teamA);
                        existing.setTeamB(teamB);
                        existing.setMatchTime(matchTime);
                        existing.setVenue(venue);
                        existing.setStage(stage);
                        if (!"COMPLETED".equals(existing.getStatus())) existing.setStatus(status);
                        if (scoreA != null) existing.setScoreA(scoreA);
                        if (scoreB != null) existing.setScoreB(scoreB);
                        existing.setTeamALabel(teamA == null ? homeTeamName : null);
                        existing.setTeamBLabel(teamB == null ? awayTeamName : null);
                        matchRepo.save(existing);
                        updated++;
                    } else {
                        Match match = new Match();
                        match.setTeamA(teamA);
                        match.setTeamB(teamB);
                        match.setMatchTime(matchTime);
                        match.setVenue(venue);
                        match.setStage(stage);
                        match.setStatus(status);
                        if (scoreA != null) match.setScoreA(scoreA);
                        if (scoreB != null) match.setScoreB(scoreB);
                        if (teamA == null) match.setTeamALabel(homeTeamName);
                        if (teamB == null) match.setTeamBLabel(awayTeamName);
                        matchRepo.save(match);
                        log.info("Inserted {} match: {} vs {} at {}", stage, homeTeamName, awayTeamName, matchTime);
                        inserted++;
                    }
                }
            } catch (Exception e) {
                log.error("Failed ESPN scoreboard for date {}: {}", date, e.getMessage());
                dateErrors++;
            }
        }

        log.info("syncMatches from ESPN: {} inserted, {} updated, {} dateErrors", inserted, updated, dateErrors);
        assignMatchNumbers();
        refreshRoundStarts();
        return (int) matchRepo.count();
    }

    private void assignMatchNumbers() {
        List<String> knockoutStages = List.of("R32", "R16", "QF", "SF", "LF", "FINAL");
        for (String stage : knockoutStages) {
            List<Match> stageMatches = matchRepo.findAll().stream()
                .filter(m -> stage.equals(m.getStage()) && m.getMatchTime() != null)
                .sorted(Comparator.comparing(Match::getMatchTime))
                .toList();
            for (int i = 0; i < stageMatches.size(); i++) {
                Match m = stageMatches.get(i);
                m.setMatchNumber(i + 1);
                matchRepo.save(m);
            }
        }
        log.info("assignMatchNumbers: done");
    }

    public int syncPlayers() {
        // Never deleteAll — players are FK-referenced by user_team_starters/bench.
        // Upsert: update existing player rows in-place, insert only truly new ones.

        // Get ESPN team IDs from teams page
        String html = webClient().get().uri(ESPN_TEAMS_URL).retrieve().bodyToMono(String.class).block();
        if (html == null) return 0;

        Map<String, String> espnTeams = new LinkedHashMap<>();
        Matcher m = Pattern.compile("/soccer/team/_/id/(\\d+)/([a-z-]+)").matcher(html);
        while (m.find()) espnTeams.putIfAbsent(m.group(1), m.group(2).replace("-", " "));

        Map<String, Team> nameToTeam = new HashMap<>();
        teamRepo.findAll().forEach(t -> nameToTeam.put(t.getName().toLowerCase(), t));

        int inserted = 0;
        int updated  = 0;
        for (Map.Entry<String, String> entry : espnTeams.entrySet()) {
            String espnId = entry.getKey();
            String espnName = entry.getValue();

            Team team = findTeamByEspnName(espnName, nameToTeam);
            if (team == null) continue;

            // Load existing players for this team into a map keyed by name (lowercase)
            Map<String, Player> existing = new HashMap<>();
            playerRepo.findByTeamId(team.getId()).forEach(p -> existing.put(p.getName().toLowerCase(), p));

            List<String[]> players = fetchSquad(espnId);
            for (String[] p : players) {
                String name      = p[0];
                String pos       = p[1];
                String jerseyStr = p.length > 2 ? p[2] : null;
                Integer jersey   = null;
                if (jerseyStr != null) {
                    try { jersey = Integer.parseInt(jerseyStr); } catch (NumberFormatException ignored) {}
                }

                Player player = existing.get(name.toLowerCase());
                if (player != null) {
                    // Update mutable fields in-place — preserves FK references
                    boolean changed = false;
                    if (!pos.equals(player.getPosition())) { player.setPosition(pos); changed = true; }
                    if (jersey != null && !jersey.equals(player.getJerseyNumber())) { player.setJerseyNumber(jersey); changed = true; }
                    if (changed) { playerRepo.save(player); updated++; }
                } else {
                    Player np = new Player();
                    np.setName(name);
                    np.setPosition(pos);
                    np.setTeam(team);
                    np.setPrice(calcPrice(name, pos));
                    if (jersey != null) np.setJerseyNumber(jersey);
                    playerRepo.save(np);
                    inserted++;
                }
            }
        }
        log.info("Synced players: {} inserted, {} updated", inserted, updated);
        int total = inserted + updated;

        // Apply FIFA prices on top of the freshly synced players
        try {
            FifaScraperService.SyncResult priceResult = fifaScraperService.syncPrices();
            log.info("FIFA price sync after player sync: {} matched, {} unmatched", priceResult.matched(), priceResult.unmatched());
        } catch (Exception e) {
            log.warn("FIFA price sync failed (non-fatal): {}", e.getMessage());
        }

        return total;
    }

    // Price tiers: [minM, maxM] in millions for each position
    private static final Map<String, long[]> PRICE_TIERS = Map.of(
        "GK",  new long[]{5_500_000L, 7_000_000L},
        "DEF", new long[]{5_500_000L, 8_000_000L},
        "MID", new long[]{6_000_000L, 9_500_000L},
        "FWD", new long[]{6_500_000L, 10_500_000L}
    );

    // Deterministic price from player name hash — same name always gets same price,
    // rounded to nearest 0.5M step so it looks like real fantasy pricing.
    private java.math.BigDecimal calcPrice(String name, String position) {
        long[] tier = PRICE_TIERS.getOrDefault(position, new long[]{6_000_000L, 8_000_000L});
        long range  = tier[1] - tier[0];
        // Use abs(hashCode) mod range, then round to nearest 500_000
        long raw    = tier[0] + (Math.abs((long) name.hashCode()) % (range + 1));
        long step   = 500_000L;
        long rounded = Math.round((double) raw / step) * step;
        rounded = Math.max(tier[0], Math.min(tier[1], rounded));
        return java.math.BigDecimal.valueOf(rounded);
    }

    private List<String[]> fetchSquad(String espnTeamId) {
        try {
            String html = webClient().get().uri(ESPN_SQUAD_URL + espnTeamId)
                    .retrieve().bodyToMono(String.class).block();
            if (html == null || html.isEmpty()) {
                log.warn("Empty response for squad {}", espnTeamId);
                return List.of();
            }

            List<String[]> players = new ArrayList<>();
            Set<String> seen = new HashSet<>();

            // Primary: match jersey number, name, espn id, position from embedded JSON
            Matcher m = Pattern.compile(
                "\"jersey\":\"(\\d+)\"[^}]{0,200}\"name\":\"([^\"]+)\",\"href\":\"[^\"]*soccer/player/_/id/(\\d+)/[^\"]*\"[^}]{0,500}\"position\":\"([GDMF])\""
            ).matcher(html);
            while (m.find()) {
                String jersey = m.group(1);
                String name   = m.group(2);
                String pid    = m.group(3);
                String pos    = POS_MAP.getOrDefault(m.group(4), "MID");
                if (seen.add(pid)) players.add(new String[]{name, pos, jersey});
            }

            // Fallback: no jersey captured
            if (players.isEmpty()) {
                Matcher m2 = Pattern.compile(
                    "\"name\":\"([^\"]+)\",\"href\":\"[^\"]*soccer/player/_/id/(\\d+)/[^\"]*\"[^}]{0,500}\"position\":\"([GDMF])\""
                ).matcher(html);
                while (m2.find()) {
                    String name = m2.group(1);
                    String pid  = m2.group(2);
                    String pos  = POS_MAP.getOrDefault(m2.group(3), "MID");
                    if (seen.add(pid)) players.add(new String[]{name, pos, null});
                }
            }

            if (players.isEmpty()) {
                log.warn("No players matched for team {} (html length: {})", espnTeamId, html.length());
            }
            return players;
        } catch (Exception e) {
            log.warn("Failed to fetch squad for ESPN team {}: {}", espnTeamId, e.getMessage());
            return List.of();
        }
    }

    // ── Sync knockout matches fresh from ESPN (delete + re-insert) ───────────

    public Map<String, Object> syncKnockoutTeams() {
        ObjectMapper mapper = new ObjectMapper();

        // 1. Delete all existing knockout matches (R32/R16/QF/SF/FINAL) and their dependents
        List<Long> knockoutIds = matchRepo.findAll().stream()
                .filter(m -> !("GROUP".equals(m.getStage())))
                .map(Match::getId)
                .toList();
        for (Long mid : knockoutIds) {
            userTeamMatchPointsRepo.deleteAll(userTeamMatchPointsRepo.findByMatchId(mid));
            matchStatsRepo.deleteAll(matchStatsRepo.findByMatchId(mid));
            userSquadRepo.deleteAll(userSquadRepo.findByMatchId(mid));
        }
        matchRepo.deleteAllById(knockoutIds);
        log.info("Deleted {} existing knockout matches", knockoutIds.size());

        // 2. Build team lookup by normalised name
        Map<String, Team> nameToTeam = new HashMap<>();
        teamRepo.findAll().forEach(t -> nameToTeam.put(normalizeTeamName(t.getName()), t));

        // 3. Fetch from ESPN and insert fresh rows
        int inserted = 0;
        int skipped  = 0;
        for (String date : KNOCKOUT_DATES) {
            try {
                String json = webClient().get().uri(ESPN_SCOREBOARD + date)
                        .retrieve().bodyToMono(String.class).block();
                if (json == null) continue;

                JsonNode events = mapper.readTree(json).path("events");
                for (JsonNode event : events) {
                    JsonNode comp = event.path("competitions").path(0);
                    String dateStr = comp.path("date").asText("");
                    if (dateStr.isBlank()) continue;

                    LocalDateTime espnTime;
                    try {
                        espnTime = java.time.OffsetDateTime.parse(dateStr)
                                .atZoneSameInstant(java.time.ZoneId.of("Asia/Kolkata"))
                                .toLocalDateTime();
                    } catch (Exception e) { continue; }

                    String homeTeamName = null, awayTeamName = null;
                    for (JsonNode c : comp.path("competitors")) {
                        String tname = c.path("team").path("displayName").asText("");
                        if (c.path("homeAway").asText().equals("home")) homeTeamName = tname;
                        else awayTeamName = tname;
                    }
                    if (homeTeamName == null || awayTeamName == null) continue;

                    Team teamA = nameToTeam.get(normalizeTeamName(homeTeamName));
                    Team teamB = nameToTeam.get(normalizeTeamName(awayTeamName));
                    if (teamA == null) log.warn("Team not found in DB: {}", homeTeamName);
                    if (teamB == null) log.warn("Team not found in DB: {}", awayTeamName);

                    // Resolve stage from ESPN season slug, notes, or date range
                    String seasonSlugKo = event.path("season").path("slug").asText("").toLowerCase();
                    String espnNoteKo   = event.path("competitions").path(0)
                            .path("notes").path(0).path("headline").asText("").toLowerCase();
                    String stage = resolveStageFromSlugOrNote(seasonSlugKo, espnNoteKo, espnTime);

                    String venue = comp.path("venue").path("fullName").asText("TBD");
                    String espnId = event.path("id").asText("");

                    Match match = new Match();
                    match.setTeamA(teamA);
                    match.setTeamB(teamB);
                    match.setMatchTime(espnTime);
                    match.setVenue(venue);
                    match.setStage(stage);
                    match.setStatus("UPCOMING");
                    if (teamA == null) match.setTeamALabel(homeTeamName);
                    if (teamB == null) match.setTeamBLabel(awayTeamName);
                    matchRepo.save(match);
                    log.info("Inserted {} match: {} vs {} at {}", stage, homeTeamName, awayTeamName, espnTime);
                    inserted++;
                }
            } catch (Exception e) {
                log.error("Failed to fetch ESPN scoreboard for date {}: {}", date, e.getMessage());
                skipped++;
            }
        }
        assignMatchNumbers();
        return Map.of("deleted", knockoutIds.size(), "inserted", inserted, "dateErrors", skipped);
    }

    private String resolveStageFromSlugOrNote(String slug, String note, LocalDateTime time) {
        // 1. ESPN season slug — most reliable (e.g. "round-of-32", "group-stage", "quarterfinals")
        if (slug.contains("group"))        return "GROUP";
        if (slug.contains("round-of-32") || slug.contains("round of 32")) return "R32";
        if (slug.contains("round-of-16") || slug.contains("round of 16")) return "R16";
        if (slug.contains("quarterfinal")) return "QF";
        if (slug.contains("semifinal"))    return "SF";
        if (slug.contains("third") || slug.contains("3rd-place")) return "LF";
        if (slug.contains("final"))        return "FINAL";
        // 2. ESPN notes headline
        if (note.contains("group"))        return "GROUP";
        if (note.contains("round of 32") || note.contains("r32")) return "R32";
        if (note.contains("round of 16") || note.contains("r16")) return "R16";
        if (note.contains("quarterfinal") || note.contains("quarter-final")) return "QF";
        if (note.contains("semifinal")    || note.contains("semi-final"))    return "SF";
        if (note.contains("third") || note.contains("3rd")) return "LF";
        if (note.contains("final")) return "FINAL";
        // 3. Date-range fallback (last resort)
        LocalDate d = time.toLocalDate();
        if (!d.isAfter(LocalDate.of(2026, 6, 27))) return "GROUP";
        if (!d.isBefore(LocalDate.of(2026, 6, 28)) && !d.isAfter(LocalDate.of(2026, 7, 4))) return "R32";
        if (!d.isBefore(LocalDate.of(2026, 7, 5)) && !d.isAfter(LocalDate.of(2026, 7, 9))) return "R16";
        if (!d.isBefore(LocalDate.of(2026, 7, 11)) && !d.isAfter(LocalDate.of(2026, 7, 13))) return "QF";
        if (!d.isBefore(LocalDate.of(2026, 7, 15)) && !d.isAfter(LocalDate.of(2026, 7, 16))) return "SF";
        return "FINAL";
    }

    private String resolveStageFromEspnNote(String note, LocalDateTime time) {
        return resolveStageFromSlugOrNote("", note, time);
    }

    private String normalizeTeamName(String name) {
        String s = name.toLowerCase().trim()
                .replaceAll("[áàâãä]", "a").replaceAll("[éèêë]", "e")
                .replaceAll("[íìîï]", "i").replaceAll("[óòôõö]", "o")
                .replaceAll("[úùûü]", "u").replaceAll("[ñ]", "n");
        s = s.replace("czechia", "czech republic")
             .replace("turkiye", "turkey").replace("türkiye", "turkey")
             .replace("bosnia-herzegovina", "bosnia and herzegovina")
             .replace("bosnia herzegovina", "bosnia and herzegovina")
             .replace("congo dr", "democratic republic of the congo")
             .replace("cote d'ivoire", "ivory coast").replace("cote divoire", "ivory coast");
        if (s.contains("korea") && !s.contains("south")) s = "south korea";
        if (s.contains("ivory coast")) s = "ivory coast";
        if (s.contains("united states") || s.equals("usa")) s = "united states";
        if (s.contains("democratic republic") && s.contains("congo")) s = "democratic republic of the congo";
        return s;
    }

    // Prefer the "type" field from the source JSON; fall back to match-ID range if absent.
    // Source types: "group", "r32", "r16", "qf", "sf", "third", "final"
    private String resolveStage(Map<String, Object> m, String matchId) {
        Object typeObj = m.get("type");
        if (typeObj instanceof String type && !type.isBlank()) {
            switch (type.toLowerCase()) {
                case "group":  return "GROUP";
                case "r32":    return "R32";
                case "r16":    return "R16";
                case "qf":     return "QF";
                case "sf":     return "SF";
                case "third":  return "SF";   // 3rd-place play-off treated as SF tier
                case "final":  return "FINAL";
            }
        }
        // Fallback: source JSON has 72 group matches (IDs 1-72) then knockout
        try {
            int id = Integer.parseInt(matchId);
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
