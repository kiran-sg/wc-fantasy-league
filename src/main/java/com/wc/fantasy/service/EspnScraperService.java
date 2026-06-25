package com.wc.fantasy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EspnScraperService {

    private final PlayerRepository playerRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String ESPN_SCOREBOARD =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/fifa.world/scoreboard?dates=";
    private static final String ESPN_SUMMARY =
            "https://site.api.espn.com/apis/site/v2/sports/soccer/fifa.world/summary?event=";

    // ── Public entry point ────────────────────────────────────────────────────

    public List<MatchPlayerStats> fetchAndBuildStats(Match match) {
        try {
            String gameId = resolveGameId(match);
            if (gameId == null) {
                log.warn("Could not resolve ESPN gameId for {} vs {}", match.getTeamA().getName(), match.getTeamB().getName());
                return Collections.emptyList();
            }
            log.info("Resolved ESPN gameId={} for {} vs {}", gameId, match.getTeamA().getName(), match.getTeamB().getName());

            String json = fetch(ESPN_SUMMARY + gameId);
            if (json == null) return Collections.emptyList();

            JsonNode root = objectMapper.readTree(json);
            return buildStats(match, root);

        } catch (Exception e) {
            log.error("ESPN fetch failed for match {} vs {}: {}", match.getTeamA().getName(), match.getTeamB().getName(), e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    // Also expose scores for AdminController to update match scoreline
    public ScoreResult fetchScore(Match match) {
        try {
            String gameId = resolveGameId(match);
            if (gameId == null) return null;

            String json = fetch(ESPN_SUMMARY + gameId);
            if (json == null) return null;

            JsonNode root = objectMapper.readTree(json);
            JsonNode competitors = root.path("header").path("competitions").path(0).path("competitors");
            int homeScore = 0, awayScore = 0;
            for (JsonNode c : competitors) {
                int score = c.path("score").asInt(0);
                boolean isHome = c.path("homeAway").asText().equals("home");
                if (isHome) homeScore = score; else awayScore = score;
            }
            return new ScoreResult(homeScore, awayScore);
        } catch (Exception e) {
            log.error("ESPN score fetch failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Step 1: resolve gameId from scoreboard ────────────────────────────────

    private String resolveGameId(Match match) throws Exception {
        String date = match.getMatchTime().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String gameId = resolveGameIdForDate(match, date);
        if (gameId != null) return gameId;
        // ESPN uses US/local timezone — a match stored as UTC next-day may be previous day on ESPN
        String prevDate = match.getMatchTime().minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        return resolveGameIdForDate(match, prevDate);
    }

    private String resolveGameIdForDate(Match match, String date) throws Exception {
        String json = fetch(ESPN_SCOREBOARD + date);
        if (json == null) return null;

        JsonNode root = objectMapper.readTree(json);
        JsonNode events = root.path("events");
        for (JsonNode event : events) {
            JsonNode comps = event.path("competitions").path(0).path("competitors");
            String nameA = null, nameB = null;
            for (JsonNode c : comps) {
                String teamName = c.path("team").path("displayName").asText("");
                if (c.path("homeAway").asText().equals("home")) nameA = teamName;
                else nameB = teamName;
            }
            if (nameA == null || nameB == null) continue;
            // Check both orderings — ESPN home/away may not match our teamA/teamB assignment
            if ((matchesTeam(nameA, match.getTeamA().getName()) && matchesTeam(nameB, match.getTeamB().getName()))
             || (matchesTeam(nameA, match.getTeamB().getName()) && matchesTeam(nameB, match.getTeamA().getName()))) {
                return event.path("id").asText(null);
            }
        }
        return null;
    }

    // ── Step 2: build MatchPlayerStats from summary JSON ─────────────────────

    private List<MatchPlayerStats> buildStats(Match match, JsonNode root) {
        List<Player> teamAPlayers = playerRepo.findByTeamId(match.getTeamA().getId());
        List<Player> teamBPlayers = playerRepo.findByTeamId(match.getTeamB().getId());

        Map<String, Player> lookup = new HashMap<>();
        for (Player p : teamAPlayers) lookup.put(normalizeName(p.getName()), p);
        for (Player p : teamBPlayers) lookup.put(normalizeName(p.getName()), p);

        // Goals conceded per team — derive from header scores
        JsonNode competitors = root.path("header").path("competitions").path(0).path("competitors");
        int homeScore = 0, awayScore = 0;
        String homeTeamEspnId = null, awayTeamEspnId = null;
        for (JsonNode c : competitors) {
            int score = c.path("score").asInt(0);
            String teamId = c.path("team").path("id").asText(null);
            if (c.path("homeAway").asText().equals("home")) {
                homeScore = score;
                homeTeamEspnId = teamId;
            } else {
                awayScore = score;
                awayTeamEspnId = teamId;
            }
        }
        int homeGoalsConceded = awayScore;
        int awayGoalsConceded = homeScore;

        // Per-player clean sheet: parse goal timing from root plays[]
        boolean hasPlaysData = root.path("plays").isArray() && root.path("plays").size() > 0;
        Map<String, List<Integer>> goalsConcededMinutes = null;
        if (hasPlaysData && homeTeamEspnId != null && awayTeamEspnId != null) {
            goalsConcededMinutes = extractGoalsConcededMinutes(root, homeTeamEspnId, awayTeamEspnId);
        }

        Map<Long, MatchPlayerStats> statsMap = new LinkedHashMap<>();

        JsonNode rosters = root.path("rosters");
        for (JsonNode teamRoster : rosters) {
            boolean isHome = teamRoster.path("homeAway").asText("home").equals("home");
            int conceded = isHome ? homeGoalsConceded : awayGoalsConceded;
            String rosterEspnId = isHome ? homeTeamEspnId : awayTeamEspnId;
            List<Integer> concededMinutesList = (goalsConcededMinutes != null && rosterEspnId != null)
                    ? goalsConcededMinutes.getOrDefault(rosterEspnId, Collections.emptyList())
                    : Collections.emptyList();

            for (JsonNode entry : teamRoster.path("roster")) {
                String displayName = entry.path("athlete").path("displayName").asText("");
                Player player = findPlayer(displayName, lookup);
                if (player == null) continue;
                if (statsMap.containsKey(player.getId())) continue;

                MatchPlayerStats s = new MatchPlayerStats();
                s.setMatch(match);
                s.setPlayer(player);

                // Stats array
                JsonNode statsArr = entry.path("stats");
                s.setGoals(getStatValue(statsArr, "totalGoals"));
                s.setAssists(getStatValue(statsArr, "goalAssists"));
                s.setYellowCards(getStatValue(statsArr, "yellowCards"));
                s.setRedCards(getStatValue(statsArr, "redCards"));
                s.setOwnGoals(getStatValue(statsArr, "ownGoals"));
                s.setSaves(getStatValue(statsArr, "saves"));
                s.setShotsOnTarget(getStatValue(statsArr, "shotsOnTarget"));

                int playerConceded = getStatValue(statsArr, "goalsConceded");
                s.setGoalsConceded(playerConceded > 0 ? playerConceded : conceded);

                int[] window = inferOnPitchWindow(entry);
                int startMin = window[0], exitMin = window[1];
                s.setMinutesPlayed(startMin < 0 ? 0 : exitMin - startMin);

                // Per-player clean sheet:
                // Player gets CS only if team conceded 0 goals from minute 0 through their exit minute.
                // This means: a sub who came on at 30' does NOT get CS if the team conceded at 20'
                // (goal was before their entry but marks the match as "dirty").
                // A starter subbed out at 60' DOES get CS if team only conceded after 60'.
                boolean cleanSheet;
                if (hasPlaysData && startMin >= 0) {
                    boolean noGoalUpToExit = concededMinutesList.stream()
                            .noneMatch(m -> m >= 0 && m <= exitMin);
                    cleanSheet = noGoalUpToExit
                            && ("GK".equals(player.getPosition()) || "DEF".equals(player.getPosition()) || "MID".equals(player.getPosition()));
                } else {
                    // Fallback when ESPN plays data unavailable
                    cleanSheet = conceded == 0
                            && s.getMinutesPlayed() > 0
                            && ("GK".equals(player.getPosition()) || "DEF".equals(player.getPosition()) || "MID".equals(player.getPosition()));
                }
                s.setCleanSheet(cleanSheet);

                s.setTotalPoints(0); // computed later by SquadService
                statsMap.put(player.getId(), s);
            }
        }

        return new ArrayList<>(statsMap.values());
    }

    // ── Goal timing extraction from root plays[] ─────────────────────────────

    // Returns map of ESPN team ID → list of minutes that team conceded a goal.
    // Regular goals count against the opposing team; own goals count against the team that scored them.
    private Map<String, List<Integer>> extractGoalsConcededMinutes(JsonNode root, String homeId, String awayId) {
        Map<String, List<Integer>> conceded = new HashMap<>();
        conceded.put(homeId, new ArrayList<>());
        conceded.put(awayId, new ArrayList<>());

        for (JsonNode play : root.path("plays")) {
            String typeText = play.path("type").path("text").asText("").toLowerCase();
            if (!typeText.contains("goal")) continue;

            int minute = parseClock(play.path("clock").path("displayValue").asText(""));
            if (minute < 0) continue;

            String scoringTeamId = play.path("team").path("id").asText(null);
            if (scoringTeamId == null) continue;

            if (typeText.contains("own")) {
                // Own goal: the team that scored it conceded
                if (conceded.containsKey(scoringTeamId)) {
                    conceded.get(scoringTeamId).add(minute);
                }
            } else {
                // Regular goal: the opposing team conceded
                String concedingTeamId = homeId.equals(scoringTeamId) ? awayId : homeId;
                if (conceded.containsKey(concedingTeamId)) {
                    conceded.get(concedingTeamId).add(minute);
                }
            }
        }

        log.info("Goal conceded minutes — home({}): {} away({}): {}", homeId, conceded.get(homeId), awayId, conceded.get(awayId));
        return conceded;
    }

    // ── On-pitch window inference: returns [startMinute, exitMinute] ─────────
    // Returns [-1, -1] for DNP players.

    private int[] inferOnPitchWindow(JsonNode entry) {
        boolean starter = entry.path("starter").asBoolean(false);
        boolean subbedIn = entry.path("subbedIn").asBoolean(false);
        boolean subbedOut = entry.path("subbedOut").asBoolean(false);

        if (!starter && !subbedIn) return new int[]{-1, -1}; // DNP

        int subMinute = -1;
        for (JsonNode play : entry.path("plays")) {
            if (play.path("substitution").asBoolean(false)) {
                String clock = play.path("clock").path("displayValue").asText("");
                subMinute = parseClock(clock);
                break;
            }
        }

        if (starter && subbedOut && subMinute > 0) return new int[]{0, subMinute};
        if (!starter && subbedIn && subMinute > 0) return new int[]{subMinute, 90};
        if (starter && !subbedOut) return new int[]{0, 90};
        return new int[]{0, 45}; // fallback
    }

    private int parseClock(String clock) {
        // format: "66'" or "90'+3'"
        try {
            String stripped = clock.replaceAll("[^0-9]", " ").trim().split("\\s+")[0];
            return Integer.parseInt(stripped);
        } catch (Exception e) {
            return -1;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int getStatValue(JsonNode statsArr, String name) {
        if (statsArr == null || !statsArr.isArray()) return 0;
        for (JsonNode stat : statsArr) {
            if (name.equals(stat.path("name").asText(""))) {
                return stat.path("value").asInt(0);
            }
        }
        return 0;
    }

    private Player findPlayer(String name, Map<String, Player> lookup) {
        String normalized = normalizeName(name);
        if (lookup.containsKey(normalized)) return lookup.get(normalized);
        // Last name match (ESPN uses "H. Kane")
        String[] parts = normalized.split("\\s+");
        String lastName = parts[parts.length - 1];
        if (lastName.length() > 2) {
            for (Map.Entry<String, Player> e : lookup.entrySet()) {
                if (e.getKey().endsWith(lastName)) return e.getValue();
            }
        }
        return null;
    }

    private boolean matchesTeam(String espnName, String dbName) {
        String e = normalizeTeamName(espnName);
        String d = normalizeTeamName(dbName);
        return e.equals(d) || e.contains(d) || d.contains(e);
    }

    private String normalizeTeamName(String name) {
        String s = name.toLowerCase().trim();
        // Accents
        s = s.replaceAll("[áàâãä]", "a").replaceAll("[éèêë]", "e")
             .replaceAll("[íìîï]", "i").replaceAll("[óòôõö]", "o")
             .replaceAll("[úùûü]", "u").replaceAll("[ñ]", "n");
        // Canonical aliases
        s = s.replace("czechia", "czech republic");
        s = s.replace("turkiye", "turkey");
        s = s.replace("türkiye", "turkey");
        s = s.replace("bosnia-herzegovina", "bosnia and herzegovina");
        s = s.replace("congo dr", "democratic republic of the congo");
        s = s.replace("cote d'ivoire", "ivory coast");
        s = s.replace("cote divoire", "ivory coast");
        // Partial aliases — normalize to a shared token so contains() works
        if (s.contains("korea") && !s.contains("south")) s = "south korea";
        if (s.contains("ivory coast")) s = "ivory coast";
        if (s.contains("united states") || s.equals("usa")) s = "united states";
        if (s.contains("democratic republic") && s.contains("congo")) s = "democratic republic of the congo";
        return s;
    }

    private String normalizeName(String name) {
        return name.toLowerCase()
                .replaceAll("[áàâãä]", "a").replaceAll("[éèêë]", "e")
                .replaceAll("[íìîï]", "i").replaceAll("[óòôõö]", "o")
                .replaceAll("[úùûü]", "u").replaceAll("[ç]", "c")
                .replaceAll("[ñ]", "n").replaceAll("[šś]", "s")
                .replaceAll("[žź]", "z").replaceAll("[čć]", "c")
                .replaceAll("[đ]", "d").replaceAll("[ř]", "r")
                .replaceAll("[^a-z\\s]", "").trim();
    }

    private String fetch(String url) {
        try {
            WebClient client = WebClient.builder()
                    .defaultHeader("User-Agent", "Mozilla/5.0")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();
            return client.get().uri(url).retrieve().bodyToMono(String.class).block();
        } catch (Exception e) {
            log.error("HTTP fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    public record ScoreResult(int homeScore, int awayScore) {}
}
