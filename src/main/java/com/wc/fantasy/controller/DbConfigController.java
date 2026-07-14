package com.wc.fantasy.controller;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/db")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class DbConfigController {

    private final TeamRepository teamRepo;
    private final PlayerRepository playerRepo;
    private final MatchRepository matchRepo;
    private final MatchPlayerStatsRepository statsRepo;
    private final UserRepository userRepo;
    private final UserTransferRecordRepository transferRecordRepo;
    private final RoundConfigRepository roundConfigRepo;

    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    // ── Table list ────────────────────────────────────────────────────────────

    @GetMapping("/tables")
    public List<Map<String, Object>> getTables() {
        return List.of(
            tableInfo("teams",                "Teams",                List.of("id","name","code","group","flagUrl","eliminated")),
            tableInfo("players",              "Players",              List.of("id","name","position","team","team_id","jerseyNumber","price","fifaPlayerName")),
            tableInfo("matches",              "Matches",              List.of("id","teamA","teamA_id","teamB","teamB_id","matchTime","venue","stage","status","scoreA","scoreB","teamALabel","teamBLabel","matchNumber")),
            tableInfo("match_player_stats",   "Match Player Stats",   List.of("id","match","match_id","player","player_id","goals","assists","yellowCards","redCards","ownGoals","cleanSheet","goalsConceded","minutesPlayed","saves","shotsOnTarget","totalPoints")),
            tableInfo("app_users",            "Users",                List.of("id","username","displayName","totalPoints","missedPoints","isAdmin","location")),
            tableInfo("user_transfer_records","Transfer Records",     List.of("id","user","user_id","stage","transfersMade","penaltyPoints")),
            tableInfo("round_config",         "Round Config",         List.of("stage","freeTransfers","countryLimit","windowOpenHour","windowCloseHour","windowTimezone","roundStart"))
        );
    }

    private Map<String, Object> tableInfo(String key, String label, List<String> columns) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key); m.put("label", label); m.put("columns", columns);
        return m;
    }

    // ── Table rows ────────────────────────────────────────────────────────────

    @GetMapping("/table/{name}")
    public ResponseEntity<List<Map<String, Object>>> getRows(@PathVariable String name,
                                                              @RequestParam(defaultValue = "") String q) {
        return switch (name) {
            case "teams"                 -> ResponseEntity.ok(teamsRows(q));
            case "players"               -> ResponseEntity.ok(playerRows(q));
            case "matches"               -> ResponseEntity.ok(matchRows(q));
            case "match_player_stats"    -> ResponseEntity.ok(statsRows(q));
            case "app_users"             -> ResponseEntity.ok(userRows(q));
            case "user_transfer_records" -> ResponseEntity.ok(transferRows(q));
            case "round_config"          -> ResponseEntity.ok(roundRows(q));
            default -> ResponseEntity.badRequest().build();
        };
    }

    // ── Update row ────────────────────────────────────────────────────────────

    @PatchMapping("/table/{name}/{id}")
    public ResponseEntity<Map<String, Object>> updateRow(@PathVariable String name,
                                                          @PathVariable String id,
                                                          @RequestBody Map<String, Object> fields) {
        try {
            return switch (name) {
                case "teams"                 -> ResponseEntity.ok(updateTeam(Long.parseLong(id), fields));
                case "players"               -> ResponseEntity.ok(updatePlayer(Long.parseLong(id), fields));
                case "matches"               -> ResponseEntity.ok(updateMatch(Long.parseLong(id), fields));
                case "match_player_stats"    -> ResponseEntity.ok(updateStats(Long.parseLong(id), fields));
                case "app_users"             -> ResponseEntity.ok(updateUser(Long.parseLong(id), fields));
                case "user_transfer_records" -> ResponseEntity.ok(updateTransfer(Long.parseLong(id), fields));
                case "round_config"          -> ResponseEntity.ok(updateRoundConfig(id, fields));
                default -> ResponseEntity.badRequest().build();
            };
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Delete row ────────────────────────────────────────────────────────────

    @DeleteMapping("/table/{name}/{id}")
    public ResponseEntity<Void> deleteRow(@PathVariable String name, @PathVariable String id) {
        try {
            switch (name) {
                case "teams"                 -> teamRepo.deleteById(Long.parseLong(id));
                case "players"               -> playerRepo.deleteById(Long.parseLong(id));
                case "matches"               -> matchRepo.deleteById(Long.parseLong(id));
                case "match_player_stats"    -> statsRepo.deleteById(Long.parseLong(id));
                case "app_users"             -> userRepo.deleteById(Long.parseLong(id));
                case "user_transfer_records" -> transferRecordRepo.deleteById(Long.parseLong(id));
                case "round_config"          -> roundConfigRepo.deleteById(id);
                default -> { return ResponseEntity.badRequest().build(); }
            }
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    // ── Row serializers ───────────────────────────────────────────────────────

    private List<Map<String, Object>> teamsRows(String q) {
        return teamRepo.findAll().stream()
            .filter(t -> q.isEmpty() || t.getName().toLowerCase().contains(q.toLowerCase()) || t.getCode().toLowerCase().contains(q.toLowerCase()))
            .map(t -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", t.getId()); m.put("name", t.getName()); m.put("code", t.getCode());
                m.put("group", t.getGroup()); m.put("flagUrl", t.getFlagUrl()); m.put("eliminated", t.getEliminated());
                return m;
            }).toList();
    }

    private List<Map<String, Object>> playerRows(String q) {
        return playerRepo.findAll().stream()
            .filter(p -> q.isEmpty() || p.getName().toLowerCase().contains(q.toLowerCase())
                || (p.getTeam() != null && p.getTeam().getName().toLowerCase().contains(q.toLowerCase()))
                || p.getPosition().toLowerCase().contains(q.toLowerCase()))
            .map(p -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId()); m.put("name", p.getName()); m.put("position", p.getPosition());
                m.put("team", p.getTeam() != null ? p.getTeam().getName() : null);
                m.put("team_id", p.getTeam() != null ? p.getTeam().getId() : null);
                m.put("jerseyNumber", p.getJerseyNumber());
                m.put("price", p.getPrice()); m.put("fifaPlayerName", p.getFifaPlayerName());
                return m;
            }).toList();
    }

    private List<Map<String, Object>> matchRows(String q) {
        return matchRepo.findAll().stream()
            .filter(m -> q.isEmpty()
                || (m.getTeamA() != null && m.getTeamA().getName().toLowerCase().contains(q.toLowerCase()))
                || (m.getTeamB() != null && m.getTeamB().getName().toLowerCase().contains(q.toLowerCase()))
                || m.getStage().toLowerCase().contains(q.toLowerCase())
                || m.getStatus().toLowerCase().contains(q.toLowerCase()))
            .sorted(Comparator.comparing(Match::getMatchTime, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(match -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", match.getId());
                row.put("teamA", match.getTeamA() != null ? match.getTeamA().getName() : null);
                row.put("teamA_id", match.getTeamA() != null ? match.getTeamA().getId() : null);
                row.put("teamB", match.getTeamB() != null ? match.getTeamB().getName() : null);
                row.put("teamB_id", match.getTeamB() != null ? match.getTeamB().getId() : null);
                row.put("matchTime", match.getMatchTime() != null ? match.getMatchTime().format(DT) : null);
                row.put("venue", match.getVenue()); row.put("stage", match.getStage()); row.put("status", match.getStatus());
                row.put("scoreA", match.getScoreA()); row.put("scoreB", match.getScoreB());
                row.put("teamALabel", match.getTeamALabel()); row.put("teamBLabel", match.getTeamBLabel());
                row.put("matchNumber", match.getMatchNumber());
                return row;
            }).toList();
    }

    private List<Map<String, Object>> statsRows(String q) {
        return statsRepo.findAll().stream()
            .filter(s -> q.isEmpty()
                || (s.getPlayer() != null && s.getPlayer().getName().toLowerCase().contains(q.toLowerCase()))
                || (s.getMatch() != null && s.getMatch().getVenue() != null && s.getMatch().getVenue().toLowerCase().contains(q.toLowerCase())))
            .map(s -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.getId());
                m.put("match", s.getMatch() != null ? (s.getMatch().getTeamA() != null ? s.getMatch().getTeamA().getName() : "") + " vs " + (s.getMatch().getTeamB() != null ? s.getMatch().getTeamB().getName() : "") : null);
                m.put("match_id", s.getMatch() != null ? s.getMatch().getId() : null);
                m.put("player", s.getPlayer() != null ? s.getPlayer().getName() : null);
                m.put("player_id", s.getPlayer() != null ? s.getPlayer().getId() : null);
                m.put("goals", s.getGoals()); m.put("assists", s.getAssists());
                m.put("yellowCards", s.getYellowCards()); m.put("redCards", s.getRedCards());
                m.put("ownGoals", s.getOwnGoals()); m.put("cleanSheet", s.getCleanSheet());
                m.put("goalsConceded", s.getGoalsConceded()); m.put("minutesPlayed", s.getMinutesPlayed());
                m.put("saves", s.getSaves()); m.put("shotsOnTarget", s.getShotsOnTarget());
                m.put("totalPoints", s.getTotalPoints());
                return m;
            }).toList();
    }

    private List<Map<String, Object>> userRows(String q) {
        return userRepo.findAll().stream()
            .filter(u -> q.isEmpty() || u.getUsername().toLowerCase().contains(q.toLowerCase())
                || (u.getDisplayName() != null && u.getDisplayName().toLowerCase().contains(q.toLowerCase())))
            .map(u -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", u.getId()); m.put("username", u.getUsername()); m.put("displayName", u.getDisplayName());
                m.put("totalPoints", u.getTotalPoints()); m.put("missedPoints", u.getMissedPoints()); m.put("isAdmin", u.getIsAdmin()); m.put("location", u.getLocation());
                return m;
            }).toList();
    }

    private List<Map<String, Object>> transferRows(String q) {
        return transferRecordRepo.findAll().stream()
            .filter(r -> q.isEmpty() || r.getStage().toLowerCase().contains(q.toLowerCase())
                || (r.getUser() != null && r.getUser().getUsername().toLowerCase().contains(q.toLowerCase())))
            .map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.getId());
                m.put("user", r.getUser() != null ? r.getUser().getUsername() : null);
                m.put("user_id", r.getUser() != null ? r.getUser().getId() : null);
                m.put("stage", r.getStage()); m.put("transfersMade", r.getTransfersMade()); m.put("penaltyPoints", r.getPenaltyPoints());
                return m;
            }).toList();
    }

    private List<Map<String, Object>> roundRows(String q) {
        return roundConfigRepo.findAll().stream()
            .filter(r -> q.isEmpty() || r.getStage().toLowerCase().contains(q.toLowerCase()))
            .map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("stage", r.getStage()); m.put("freeTransfers", r.getFreeTransfers());
                m.put("countryLimit", r.getCountryLimit()); m.put("windowOpenHour", r.getWindowOpenHour());
                m.put("windowCloseHour", r.getWindowCloseHour()); m.put("windowTimezone", r.getWindowTimezone());
                m.put("roundStart", r.getRoundStart() != null ? r.getRoundStart().format(DT) : null);
                return m;
            }).toList();
    }

    // ── Update handlers ───────────────────────────────────────────────────────

    private Map<String, Object> updateTeam(Long id, Map<String, Object> f) {
        Team t = teamRepo.findById(id).orElseThrow();
        if (f.containsKey("name"))      t.setName(str(f, "name"));
        if (f.containsKey("code"))      t.setCode(str(f, "code"));
        if (f.containsKey("group"))     t.setGroup(str(f, "group"));
        if (f.containsKey("flagUrl"))   t.setFlagUrl(str(f, "flagUrl"));
        if (f.containsKey("eliminated")) t.setEliminated(bool(f, "eliminated"));
        teamRepo.save(t);
        return Map.of("status", "ok");
    }

    private Map<String, Object> updatePlayer(Long id, Map<String, Object> f) {
        Player p = playerRepo.findById(id).orElseThrow();
        if (f.containsKey("name"))          p.setName(str(f, "name"));
        if (f.containsKey("position"))      p.setPosition(str(f, "position"));
        if (f.containsKey("team_id"))       p.setTeam(teamRepo.findById(longVal(f, "team_id")).orElseThrow());
        if (f.containsKey("jerseyNumber"))  p.setJerseyNumber(intVal(f, "jerseyNumber"));
        if (f.containsKey("price"))         p.setPrice(BigDecimal.valueOf(doubleVal(f, "price")));
        if (f.containsKey("fifaPlayerName")) p.setFifaPlayerName(str(f, "fifaPlayerName"));
        playerRepo.save(p);
        return Map.of("status", "ok");
    }

    private Map<String, Object> updateMatch(Long id, Map<String, Object> f) {
        Match m = matchRepo.findById(id).orElseThrow();
        if (f.containsKey("teamA_id"))    m.setTeamA(teamRepo.findById(longVal(f, "teamA_id")).orElseThrow());
        if (f.containsKey("teamB_id"))    m.setTeamB(teamRepo.findById(longVal(f, "teamB_id")).orElseThrow());
        if (f.containsKey("matchTime"))   m.setMatchTime(LocalDateTime.parse(str(f, "matchTime"), DT));
        if (f.containsKey("venue"))       m.setVenue(str(f, "venue"));
        if (f.containsKey("stage"))       m.setStage(str(f, "stage"));
        if (f.containsKey("status"))      m.setStatus(str(f, "status"));
        if (f.containsKey("scoreA"))      m.setScoreA(intVal(f, "scoreA"));
        if (f.containsKey("scoreB"))      m.setScoreB(intVal(f, "scoreB"));
        if (f.containsKey("teamALabel"))  m.setTeamALabel(str(f, "teamALabel"));
        if (f.containsKey("teamBLabel"))  m.setTeamBLabel(str(f, "teamBLabel"));
        if (f.containsKey("matchNumber")) m.setMatchNumber(intVal(f, "matchNumber"));
        matchRepo.save(m);
        return Map.of("status", "ok");
    }

    private Map<String, Object> updateStats(Long id, Map<String, Object> f) {
        MatchPlayerStats s = statsRepo.findById(id).orElseThrow();
        if (f.containsKey("match_id"))      s.setMatch(matchRepo.findById(longVal(f, "match_id")).orElseThrow());
        if (f.containsKey("player_id"))     s.setPlayer(playerRepo.findById(longVal(f, "player_id")).orElseThrow());
        if (f.containsKey("goals"))         s.setGoals(intVal(f, "goals"));
        if (f.containsKey("assists"))       s.setAssists(intVal(f, "assists"));
        if (f.containsKey("yellowCards"))   s.setYellowCards(intVal(f, "yellowCards"));
        if (f.containsKey("redCards"))      s.setRedCards(intVal(f, "redCards"));
        if (f.containsKey("ownGoals"))      s.setOwnGoals(intVal(f, "ownGoals"));
        if (f.containsKey("cleanSheet"))    s.setCleanSheet(bool(f, "cleanSheet"));
        if (f.containsKey("goalsConceded")) s.setGoalsConceded(intVal(f, "goalsConceded"));
        if (f.containsKey("minutesPlayed")) s.setMinutesPlayed(intVal(f, "minutesPlayed"));
        if (f.containsKey("saves"))         s.setSaves(intVal(f, "saves"));
        if (f.containsKey("shotsOnTarget")) s.setShotsOnTarget(intVal(f, "shotsOnTarget"));
        if (f.containsKey("totalPoints"))   s.setTotalPoints(intVal(f, "totalPoints"));
        statsRepo.save(s);
        return Map.of("status", "ok");
    }

    private Map<String, Object> updateUser(Long id, Map<String, Object> f) {
        AppUser u = userRepo.findById(id).orElseThrow();
        if (f.containsKey("username"))     u.setUsername(str(f, "username"));
        if (f.containsKey("displayName"))  u.setDisplayName(str(f, "displayName"));
        if (f.containsKey("totalPoints"))   u.setTotalPoints(intVal(f, "totalPoints"));
        if (f.containsKey("missedPoints"))  u.setMissedPoints(intVal(f, "missedPoints"));
        if (f.containsKey("isAdmin"))       u.setIsAdmin(bool(f, "isAdmin"));
        if (f.containsKey("location"))     u.setLocation(str(f, "location"));
        userRepo.save(u);
        return Map.of("status", "ok");
    }

    private Map<String, Object> updateTransfer(Long id, Map<String, Object> f) {
        UserTransferRecord r = transferRecordRepo.findById(id).orElseThrow();
        if (f.containsKey("user_id"))       r.setUser(userRepo.findById(longVal(f, "user_id")).orElseThrow());
        if (f.containsKey("stage"))         r.setStage(str(f, "stage"));
        if (f.containsKey("transfersMade")) r.setTransfersMade(intVal(f, "transfersMade"));
        if (f.containsKey("penaltyPoints")) r.setPenaltyPoints(intVal(f, "penaltyPoints"));
        transferRecordRepo.save(r);
        return Map.of("status", "ok");
    }

    private Map<String, Object> updateRoundConfig(String stage, Map<String, Object> f) {
        RoundConfig r = roundConfigRepo.findById(stage).orElseThrow();
        if (f.containsKey("freeTransfers"))   r.setFreeTransfers(intVal(f, "freeTransfers"));
        if (f.containsKey("countryLimit"))    r.setCountryLimit(intVal(f, "countryLimit"));
        if (f.containsKey("windowOpenHour"))  r.setWindowOpenHour(intVal(f, "windowOpenHour"));
        if (f.containsKey("windowCloseHour")) r.setWindowCloseHour(intVal(f, "windowCloseHour"));
        if (f.containsKey("windowTimezone"))  r.setWindowTimezone(str(f, "windowTimezone"));
        if (f.containsKey("roundStart"))      r.setRoundStart(str(f, "roundStart") == null ? null : LocalDateTime.parse(str(f, "roundStart"), DT));
        roundConfigRepo.save(r);
        return Map.of("status", "ok");
    }

    // ── Value helpers ─────────────────────────────────────────────────────────

    private String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null || "".equals(v) ? null : v.toString();
    }
    private int intVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0;
        if (v instanceof Number n) return n.intValue();
        return Integer.parseInt(v.toString());
    }
    private long longVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0L;
        if (v instanceof Number n) return n.longValue();
        return Long.parseLong(v.toString());
    }
    private double doubleVal(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v == null) return 0d;
        if (v instanceof Number n) return n.doubleValue();
        return Double.parseDouble(v.toString());
    }
    private boolean bool(Map<String, Object> m, String k) {
        Object v = m.get(k);
        if (v instanceof Boolean b) return b;
        return "true".equalsIgnoreCase(String.valueOf(v));
    }
}
