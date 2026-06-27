package com.wc.fantasy.controller;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import com.wc.fantasy.service.EspnScraperService;
import com.wc.fantasy.service.FifaScraperService;
import com.wc.fantasy.service.SquadService;
import com.wc.fantasy.service.UserTeamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class AdminController {

    private final MatchRepository matchRepo;
    private final MatchPlayerStatsRepository statsRepo;
    private final UserSquadRepository squadRepo;
    private final SquadService squadService;
    private final EspnScraperService scraperService;
    private final UserTeamService userTeamService;
    private final FifaScraperService fifaScraperService;
    private final com.wc.fantasy.repository.UserRepository userRepo;
    private final com.wc.fantasy.repository.PlayerRepository playerRepo;
    private final com.wc.fantasy.repository.UserTeamRepository teamRepo;
    private final com.wc.fantasy.repository.UserTeamMatchPointsRepository matchPointsRepo;
    private final com.wc.fantasy.repository.UserTransferRecordRepository transferRecordRepo;

    // ── User management ──────────────────────────────────────────────────────

    @GetMapping("/users")
    public List<com.wc.fantasy.model.AppUser> listUsers() {
        return userRepo.findAll();
    }

    @PostMapping("/users")
    public Map<String, Object> createUser(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        if (username == null || username.isBlank())
            return Map.of("status", "error", "message", "Username is required");
        if (userRepo.findByUsername(username).isPresent())
            return Map.of("status", "error", "message", "Username already exists");
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setDisplayName(body.getOrDefault("displayName", username));
        user.setLocation(body.get("location"));
        user.setIsAdmin(Boolean.parseBoolean(body.getOrDefault("isAdmin", "false")));
        userRepo.save(user);
        return Map.of("status", "success", "userId", user.getId());
    }

    // Location display name → DB code
    private static final Map<String, String> LOCATION_MAP = Map.of(
            "trivandrum", "TVM",
            "tvm",        "TVM",
            "pune",       "Pune",
            "bangalore",  "BLR",
            "blr",        "BLR",
            "dubai",      "DXB",
            "dxb",        "DXB"
    );

    private String normalizeLocation(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String key = raw.trim().toLowerCase();
        return LOCATION_MAP.getOrDefault(key, raw.trim().toUpperCase());
    }

    private String cellStr(Cell cell) {
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default      -> null;
        };
    }

    @DeleteMapping("/players/{id}")
    public ResponseEntity<Map<String, Object>> deletePlayer(@PathVariable Long id) {
        com.wc.fantasy.model.Player player = playerRepo.findById(id).orElse(null);
        if (player == null) return ResponseEntity.notFound().build();

        // Check usage in user teams (starters/bench/captain/vc)
        boolean inTeam = teamRepo.findAll().stream().anyMatch(t ->
            (t.getStarters() != null && t.getStarters().stream().anyMatch(p -> p.getId().equals(id))) ||
            (t.getBench()    != null && t.getBench().stream().anyMatch(p -> p.getId().equals(id))) ||
            (t.getCaptain()  != null && t.getCaptain().getId().equals(id)) ||
            (t.getViceCaptain() != null && t.getViceCaptain().getId().equals(id))
        );
        if (inTeam) return ResponseEntity.badRequest()
                .body(Map.of("error", "Player is in one or more user teams and cannot be deleted."));

        // Check usage in match stats
        boolean inStats = statsRepo.findAll().stream().anyMatch(s -> s.getPlayer() != null && s.getPlayer().getId().equals(id));
        if (inStats) return ResponseEntity.badRequest()
                .body(Map.of("error", "Player has match stats recorded and cannot be deleted."));

        playerRepo.delete(player);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    @PatchMapping("/players/{id}/price")
    public ResponseEntity<Map<String, Object>> updatePlayerPrice(
            @PathVariable Long id,
            @RequestParam("value") long priceInUnits) {
        return playerRepo.findById(id).map(p -> {
            p.setPrice(java.math.BigDecimal.valueOf(priceInUnits));
            playerRepo.save(p);
            return ResponseEntity.ok(Map.<String, Object>of(
                    "id", p.getId(), "name", p.getName(),
                    "price", p.getPrice().longValue()));
        }).orElse(ResponseEntity.notFound().<Map<String, Object>>build());
    }

    @PatchMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> updateUser(@PathVariable Long id, @RequestBody Map<String, String> body) {
        com.wc.fantasy.model.AppUser user = userRepo.findById(id).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        if (body.containsKey("location")) {
            user.setLocation(normalizeLocation(body.get("location")));
        }
        userRepo.save(user);
        return ResponseEntity.ok(Map.<String, Object>of("id", user.getId(), "location", user.getLocation() != null ? user.getLocation() : ""));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<Map<String, Object>> deleteUser(@PathVariable Long id) {
        com.wc.fantasy.model.AppUser user = userRepo.findById(id).orElse(null);
        if (user == null) return ResponseEntity.notFound().build();
        if (Boolean.TRUE.equals(user.getIsAdmin()))
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot delete admin users"));
        // Delete transfer records
        transferRecordRepo.deleteAll(transferRecordRepo.findByUserId(id));
        // Delete match points + team
        teamRepo.findByUserId(id).ifPresent(team -> {
            matchPointsRepo.deleteAll(matchPointsRepo.findByUserTeamId(team.getId()));
            teamRepo.delete(team);
        });
        userRepo.delete(user);
        return ResponseEntity.ok(Map.of("deleted", id));
    }

    @DeleteMapping("/users")
    public ResponseEntity<Map<String, Object>> deleteAllNonAdminUsers() {
        List<com.wc.fantasy.model.AppUser> nonAdmins = userRepo.findAll().stream()
                .filter(u -> !Boolean.TRUE.equals(u.getIsAdmin()))
                .toList();
        userRepo.deleteAll(nonAdmins);
        return ResponseEntity.ok(Map.of("deleted", nonAdmins.size()));
    }

    @PostMapping("/users/bulk-upload")
    public ResponseEntity<Map<String, Object>> bulkUploadUsers(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        int created = 0, skipped = 0;
        List<String> errors = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            // Discover column indices by header name
            int colName = -1, colLocation = -1, colHash = -1;
            for (Cell c : header) {
                String h = cellStr(c);
                if (h == null) continue;
                String hl = h.toLowerCase();
                if (hl.contains("full name"))     colName     = c.getColumnIndex();
                else if (hl.contains("location")) colLocation = c.getColumnIndex();
                else if (hl.contains("hash"))     colHash     = c.getColumnIndex();
            }
            if (colHash == -1) return ResponseEntity.badRequest().body(Map.of("error", "Could not find 'Hash ID' column in spreadsheet"));
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String username    = cellStr(row.getCell(colHash));
                String displayName = colName     >= 0 ? cellStr(row.getCell(colName))     : null;
                String locationRaw = colLocation >= 0 ? cellStr(row.getCell(colLocation)) : null;
                if (username == null || username.isBlank()) { skipped++; continue; }
                if (userRepo.findByUsername(username).isPresent()) {
                    errors.add("Row " + (i + 1) + ": username '" + username + "' already exists — skipped");
                    skipped++;
                    continue;
                }
                AppUser u = new AppUser();
                u.setUsername(username);
                u.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : username);
                u.setLocation(normalizeLocation(locationRaw));
                u.setIsAdmin(false);
                userRepo.save(u);
                created++;
            }
        } catch (Exception e) {
            log.error("Bulk upload failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to parse file: " + e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("created", created, "skipped", skipped, "errors", errors));
    }

    @PostMapping("/update-scores/{matchId}")
    public Map<String, Object> updateScores(@PathVariable Long matchId) {
        Match match = matchRepo.findById(matchId)
                .orElseThrow(() -> new IllegalArgumentException("Match not found"));

        // Block fetch until at least 1.5 hours after kick-off
        if (match.getMatchTime() != null) {
            java.time.LocalDateTime earliest = match.getMatchTime().plusMinutes(90);
            if (java.time.LocalDateTime.now().isBefore(earliest)) {
                long minsLeft = java.time.Duration.between(java.time.LocalDateTime.now(), earliest).toMinutes() + 1;
                return Map.of("status", "error",
                        "message", "Too early — fetch available in " + minsLeft + " min (1.5 hrs after kick-off).");
            }
        }

        List<MatchPlayerStats> stats = scraperService.fetchAndBuildStats(match);
        if (stats.isEmpty()) {
            return Map.of("status", "error", "message", "Could not fetch match data from ESPN. Match may not be finished yet.");
        }

        // Update match score from ESPN
        EspnScraperService.ScoreResult score = scraperService.fetchScore(match);
        if (score != null) {
            match.setScoreA(score.homeScore());
            match.setScoreB(score.awayScore());
        }
        match.setStatus("COMPLETED");
        matchRepo.save(match);

        statsRepo.deleteAll(statsRepo.findByMatchId(matchId));
        statsRepo.saveAll(stats);

        // Calculate points for old per-match squads (UserSquad model, kept for backwards compat)
        squadService.calculatePoints(matchId);

        // Calculate points for persistent user teams (new model)
        userTeamService.calculatePointsForMatch(matchId, match);

        return Map.of(
                "status", "success",
                "matchId", matchId,
                "scoreA", match.getScoreA() != null ? match.getScoreA() : 0,
                "scoreB", match.getScoreB() != null ? match.getScoreB() : 0,
                "statsCount", stats.size()
        );
    }

    @PostMapping("/sync-fifa-prices")
    public Map<String, Object> syncFifaPrices() {
        try {
            FifaScraperService.SyncResult result = fifaScraperService.syncPrices();
            return Map.of(
                    "status", "success",
                    "matched", result.matched(),
                    "unmatched", result.unmatched(),
                    "unmatchedNames", result.unmatchedNames()
            );
        } catch (Exception e) {
            return Map.of("status", "error", "message", e.getMessage());
        }
    }

    @GetMapping("/match-stats/{matchId}")
    public List<MatchPlayerStats> getMatchStats(@PathVariable Long matchId) {
        return statsRepo.findByMatchId(matchId);
    }

    @GetMapping("/match-squads/{matchId}")
    public List<UserSquad> getMatchSquads(@PathVariable Long matchId) {
        return squadRepo.findByMatchId(matchId);
    }

    @GetMapping("/matches")
    public List<Match> getAllMatches() {
        return matchRepo.findAll(org.springframework.data.domain.Sort.by("matchTime"));
    }

}
