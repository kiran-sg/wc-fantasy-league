package com.wc.fantasy.service;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class EspnScraperService {

    private final PlayerRepository playerRepo;
    private static final String ESPN_SCOREBOARD = "https://www.espn.com/soccer/scoreboard/_/league/fifa.world/date/";

    public ScrapedMatchResult scrapeMatch(Match match) {
        String date = match.getMatchTime().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String url = ESPN_SCOREBOARD + date;

        try {
            WebClient client = WebClient.builder()
                    .defaultHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(10 * 1024 * 1024))
                    .build();

            String html = client.get().uri(url).retrieve().bodyToMono(String.class).block();
            if (html == null || html.isEmpty()) {
                log.warn("Empty response from ESPN for {}", url);
                return null;
            }

            String teamAName = match.getTeamA().getName();
            String teamBName = match.getTeamB().getName();

            // Extract all team names and scores in order from the page
            List<String> teamNames = extractAll(html, "ScoreCell__TeamName ScoreCell__TeamName--shortDisplayName db\\\">([^<]+)</div>");
            List<String> scores = extractAll(html, "ScoreCell__Score[^>]*>([0-9]+)</div>");

            log.info("ESPN page for {}: found {} teams, {} scores", date, teamNames.size(), scores.size());

            // Teams come in pairs (home, away), scores come in same order
            for (int i = 0; i < teamNames.size() - 1; i += 2) {
                String espnHome = teamNames.get(i);
                String espnAway = teamNames.get(i + 1);

                if (matchesTeam(espnHome, teamAName) && matchesTeam(espnAway, teamBName)) {
                    int homeScore = Integer.parseInt(scores.get(i));
                    int awayScore = Integer.parseInt(scores.get(i + 1));

                    // Extract goal scorers
                    // All goal scorers on the page are in order by match, home team first then away
                    // Find all goals between this match's scoreboard section and the next one
                    // Use the team name marker to find the right performer section
                    List<GoalEvent> homeGoals = new ArrayList<>();
                    List<GoalEvent> awayGoals = new ArrayList<>();

                    // Find all "SoccerPerformers__Competitor__Team__Name" entries in order
                    // For our match (index i/2), we need performer entries at position matchIdx*2 and matchIdx*2+1
                    int matchIdx = i / 2;
                    List<Integer> perfPositions = new ArrayList<>();
                    int searchFrom = 0;
                    String perfMarker = "SoccerPerformers__Competitor__Team__Name\">";
                    while (true) {
                        int pos = html.indexOf(perfMarker, searchFrom);
                        if (pos < 0) break;
                        perfPositions.add(pos);
                        searchFrom = pos + perfMarker.length();
                    }

                    int homeIdx = matchIdx * 2;
                    int awayIdx = matchIdx * 2 + 1;
                    if (homeIdx < perfPositions.size()) {
                        int start = perfPositions.get(homeIdx);
                        int end = awayIdx < perfPositions.size() ? perfPositions.get(awayIdx) : start + 2000;
                        homeGoals = parseGoalsFromSection(html.substring(start, Math.min(end, html.length())));
                    }
                    if (awayIdx < perfPositions.size()) {
                        int start = perfPositions.get(awayIdx);
                        int end = (awayIdx + 1) < perfPositions.size() ? perfPositions.get(awayIdx + 1) : start + 2000;
                        awayGoals = parseGoalsFromSection(html.substring(start, Math.min(end, html.length())));
                    }

                    log.info("Scraped: {} {}-{} {} | Scorers: home={}, away={}",
                            espnHome, homeScore, awayScore, espnAway, homeGoals.size(), awayGoals.size());

                    return new ScrapedMatchResult(homeScore, awayScore, homeGoals, awayGoals);
                }
            }

            log.warn("Could not find match {} vs {} in ESPN teams: {}", teamAName, teamBName, teamNames);
            return null;

        } catch (Exception e) {
            log.error("ESPN scrape failed for match {} vs {}: {}", match.getTeamA().getName(), match.getTeamB().getName(), e.getMessage(), e);
            return null;
        }
    }

    private List<GoalEvent> parseGoalsFromSection(String section) {
        List<GoalEvent> goals = new ArrayList<>();
        // Decode HTML entities first
        section = section.replace("&#x27;", "'").replace("&amp;", "&").replace("<!-- -->", "");
        // ESPN pattern: Soccer__PlayerName" ...>H. Kane</a><span class="GoalScore__Time"> - 12' Pen, 42'</span>
        Pattern p = Pattern.compile("Soccer__PlayerName[^>]*>([^<]+)</a>.*?GoalScore__Time\">([^<]+)</span>", Pattern.DOTALL);
        Matcher m = p.matcher(section);
        while (m.find()) {
            String playerName = m.group(1).trim();
            String timeStr = m.group(2).replaceAll("[\\s-]+", " ").trim();
            String[] parts = timeStr.split(",");
            for (String part : parts) {
                part = part.trim();
                if (part.isEmpty()) continue;
                boolean isPen = part.contains("Pen");
                boolean isOG = part.contains("OG");
                goals.add(new GoalEvent(playerName, part, isPen, isOG));
            }
        }
        return goals;
    }

    private List<String> extractAll(String text, String regex) {
        List<String> results = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(text);
        while (m.find()) results.add(m.group(1));
        return results;
    }

    private boolean matchesTeam(String espnName, String dbName) {
        String e = espnName.toLowerCase().trim();
        String d = dbName.toLowerCase().trim();
        if (e.equals(d)) return true;
        if (e.contains(d) || d.contains(e)) return true;
        // Handle special cases
        if (d.contains("democratic republic") && e.contains("congo")) return true;
        if (d.contains("ivory coast") && e.contains("ivoire")) return true;
        if (d.contains("south korea") && e.contains("korea")) return true;
        if (d.contains("united states") && (e.contains("usa") || e.contains("united states"))) return true;
        return false;
    }

    public List<MatchPlayerStats> buildStats(Match match, ScrapedMatchResult result) {
        List<Player> teamAPlayers = playerRepo.findByTeamId(match.getTeamA().getId());
        List<Player> teamBPlayers = playerRepo.findByTeamId(match.getTeamB().getId());
        List<MatchPlayerStats> stats = new ArrayList<>();

        Map<String, Player> lookup = new HashMap<>();
        for (Player p : teamAPlayers) lookup.put(normalizeName(p.getName()), p);
        for (Player p : teamBPlayers) lookup.put(normalizeName(p.getName()), p);

        boolean homeCleanSheet = result.awayScore == 0;
        boolean awayCleanSheet = result.homeScore == 0;

        // Starting XI from each team (first 11)
        List<Player> homeStarting = teamAPlayers.subList(0, Math.min(11, teamAPlayers.size()));
        List<Player> awayStarting = teamBPlayers.subList(0, Math.min(11, teamBPlayers.size()));

        for (Player p : homeStarting) {
            stats.add(buildPlayerStat(match, p, result.homeGoals, homeCleanSheet, lookup));
        }
        for (Player p : awayStarting) {
            stats.add(buildPlayerStat(match, p, result.awayGoals, awayCleanSheet, lookup));
        }

        // Add goal scorers not in starting XI
        for (GoalEvent goal : result.homeGoals) {
            Player player = findPlayer(goal.playerName, lookup);
            if (player != null && stats.stream().noneMatch(s -> s.getPlayer().getId().equals(player.getId()))) {
                stats.add(buildPlayerStat(match, player, result.homeGoals, homeCleanSheet, lookup));
            }
        }
        for (GoalEvent goal : result.awayGoals) {
            Player player = findPlayer(goal.playerName, lookup);
            if (player != null && stats.stream().noneMatch(s -> s.getPlayer().getId().equals(player.getId()))) {
                stats.add(buildPlayerStat(match, player, result.awayGoals, awayCleanSheet, lookup));
            }
        }

        // MOM = top scorer
        stats.stream()
                .max(Comparator.comparingInt(s -> s.getGoals() * 2 + s.getAssists()))
                .ifPresent(s -> s.setManOfMatch(true));

        return stats;
    }

    private MatchPlayerStats buildPlayerStat(Match match, Player player, List<GoalEvent> teamGoals, boolean cleanSheet, Map<String, Player> lookup) {
        MatchPlayerStats s = new MatchPlayerStats();
        s.setMatch(match);
        s.setPlayer(player);
        s.setMinutesPlayed(90);
        s.setGoals(0);
        s.setAssists(0);
        s.setYellowCards(0);
        s.setRedCards(0);
        s.setManOfMatch(false);
        s.setCleanSheet(false);
        s.setTotalPoints(0);

        for (GoalEvent goal : teamGoals) {
            Player gp = findPlayer(goal.playerName, lookup);
            if (gp != null && gp.getId().equals(player.getId()) && !goal.isOwnGoal) {
                s.setGoals(s.getGoals() + 1);
            }
        }

        if (cleanSheet && ("GK".equals(player.getPosition()) || "DEF".equals(player.getPosition()))) {
            s.setCleanSheet(true);
        }

        return s;
    }

    private Player findPlayer(String name, Map<String, Player> lookup) {
        String normalized = normalizeName(name);
        // Direct match
        for (Map.Entry<String, Player> e : lookup.entrySet()) {
            if (e.getKey().equals(normalized)) return e.getValue();
        }
        // Last name match (ESPN uses "H. Kane", "J. Bellingham")
        String[] parts = normalized.split("\\s+");
        String lastName = parts[parts.length - 1];
        if (lastName.length() > 2) {
            for (Map.Entry<String, Player> e : lookup.entrySet()) {
                if (e.getKey().endsWith(lastName)) return e.getValue();
            }
        }
        return null;
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

    public record GoalEvent(String playerName, String minute, boolean isPenalty, boolean isOwnGoal) {}
    public record ScrapedMatchResult(int homeScore, int awayScore, List<GoalEvent> homeGoals, List<GoalEvent> awayGoals) {}
}
