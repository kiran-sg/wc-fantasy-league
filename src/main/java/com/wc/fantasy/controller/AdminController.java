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
    private final com.wc.fantasy.repository.TeamRepository teamRepo;
    private final com.wc.fantasy.repository.UserTeamRepository userTeamRepo;
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

    @PostMapping("/players")
    public ResponseEntity<Map<String, Object>> createPlayer(@RequestBody Map<String, Object> body) {
        String name = (String) body.get("name");
        if (name == null || name.isBlank())
            return ResponseEntity.badRequest().body(Map.of("error", "Name is required"));
        String position = (String) body.get("position");
        if (position == null || !List.of("GK","DEF","MID","FWD").contains(position))
            return ResponseEntity.badRequest().body(Map.of("error", "Valid position required (GK/DEF/MID/FWD)"));
        Object teamIdObj = body.get("teamId");
        if (teamIdObj == null)
            return ResponseEntity.badRequest().body(Map.of("error", "teamId is required"));
        Long teamId = Long.valueOf(teamIdObj.toString());
        com.wc.fantasy.model.Team team = teamRepo.findById(teamId).orElse(null);
        if (team == null)
            return ResponseEntity.badRequest().body(Map.of("error", "Team not found"));
        Object priceObj = body.get("price");
        long price = priceObj != null ? Long.valueOf(priceObj.toString()) : 6_000_000L;

        com.wc.fantasy.model.Player player = new com.wc.fantasy.model.Player();
        player.setName(name.trim());
        player.setPosition(position);
        player.setTeam(team);
        player.setPrice(java.math.BigDecimal.valueOf(price));
        playerRepo.save(player);
        return ResponseEntity.ok(Map.of("id", player.getId(), "name", player.getName(),
                "position", player.getPosition(), "team", team.getName(), "price", price));
    }

    @DeleteMapping("/players/{id}")
    public ResponseEntity<Map<String, Object>> deletePlayer(@PathVariable Long id) {
        com.wc.fantasy.model.Player player = playerRepo.findById(id).orElse(null);
        if (player == null) return ResponseEntity.notFound().build();

        // Check usage in user teams (starters/bench/captain/vc)
        boolean inTeam = userTeamRepo.findAll().stream().anyMatch(t ->
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

    @PatchMapping("/players/{id}")
    public ResponseEntity<Map<String, Object>> updatePlayer(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return playerRepo.findById(id).map(p -> {
            if (body.containsKey("position")) {
                String pos = (String) body.get("position");
                if (List.of("GK", "DEF", "MID", "FWD").contains(pos)) p.setPosition(pos);
            }
            if (body.containsKey("price")) {
                p.setPrice(java.math.BigDecimal.valueOf(Long.valueOf(body.get("price").toString())));
            }
            playerRepo.save(p);
            return ResponseEntity.ok(Map.<String, Object>of(
                    "id", p.getId(), "name", p.getName(),
                    "position", p.getPosition(), "price", p.getPrice().longValue()));
        }).orElse(ResponseEntity.notFound().<Map<String, Object>>build());
    }

    @PatchMapping("/teams/{id}/eliminated")
    public ResponseEntity<Map<String, Object>> setTeamEliminated(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return teamRepo.findById(id).map(t -> {
            t.setEliminated(Boolean.parseBoolean(body.getOrDefault("eliminated", "false").toString()));
            teamRepo.save(t);
            return ResponseEntity.ok(Map.<String, Object>of(
                    "id", t.getId(), "name", t.getName(), "eliminated", t.getEliminated()));
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
        userTeamRepo.findByUserId(id).ifPresent(team -> {
            matchPointsRepo.deleteAll(matchPointsRepo.findByUserTeamId(team.getId()));
            userTeamRepo.delete(team);
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

    @PostMapping("/players/price-upload")
    public ResponseEntity<Map<String, Object>> uploadPlayerPrices(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));

        // Build team name → id lookup (case-insensitive)
        Map<String, Long> teamLookup = new java.util.HashMap<>();
        for (com.wc.fantasy.model.Team t : teamRepo.findAll()) {
            teamLookup.put(t.getName().toLowerCase().trim(), t.getId());
        }

        int updated = 0, skipped = 0, notFound = 0;
        List<String> errors = new ArrayList<>();

        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);

            int colName = -1, colCountry = -1, colPrice = -1, colPriceOverride = -1;
            for (Cell c : header) {
                String h = cellStr(c);
                if (h == null) continue;
                String hl = h.trim();
                if (hl.equals("App Player Name"))             colName          = c.getColumnIndex();
                else if (hl.equals("App Country"))            colCountry       = c.getColumnIndex();
                else if (hl.equals("Price FIFA ($m)"))        colPrice         = c.getColumnIndex();
                else if (hl.equals("Price to update in App")) colPriceOverride = c.getColumnIndex();
            }

            if (colName == -1 || colCountry == -1 || colPrice == -1)
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Required columns not found: 'App Player Name', 'App Country', 'Price FIFA ($m)'"));

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                String playerName = cellStr(row.getCell(colName));
                String country    = cellStr(row.getCell(colCountry));
                if (playerName == null || playerName.isBlank()) { skipped++; continue; }

                // Determine which price to use: override column takes priority if set
                String overrideRaw = colPriceOverride >= 0 ? cellStr(row.getCell(colPriceOverride)) : null;
                boolean useOverride = overrideRaw != null
                    && !overrideRaw.isBlank()
                    && !overrideRaw.equalsIgnoreCase("No change")
                    && !overrideRaw.equalsIgnoreCase("No FIFA match");

                java.math.BigDecimal price;
                try {
                    String priceRaw = useOverride ? overrideRaw : cellStr(row.getCell(colPrice));
                    if (priceRaw == null || priceRaw.isBlank()) { skipped++; continue; }
                    double priceMillions = Double.parseDouble(priceRaw.replaceAll("[^0-9.]", ""));
                    price = java.math.BigDecimal.valueOf(priceMillions * 1_000_000)
                            .setScale(1, java.math.RoundingMode.HALF_UP);
                } catch (NumberFormatException e) {
                    errors.add("Row " + (i + 1) + " (" + playerName + "): invalid price value — skipped");
                    skipped++;
                    continue;
                }

                // Resolve team
                Long teamId = country != null ? teamLookup.get(country.toLowerCase().trim()) : null;

                // Find player: match by name + team if team found, else name-only
                List<com.wc.fantasy.model.Player> candidates;
                if (teamId != null) {
                    candidates = playerRepo.findByTeamId(teamId).stream()
                        .filter(p -> p.getName().equalsIgnoreCase(playerName))
                        .toList();
                } else {
                    candidates = playerRepo.findAll().stream()
                        .filter(p -> p.getName().equalsIgnoreCase(playerName))
                        .toList();
                }

                if (candidates.isEmpty()) {
                    errors.add("Row " + (i + 1) + ": player '" + playerName + "'" +
                        (country != null ? " (" + country + ")" : "") + " not found — skipped");
                    notFound++;
                    continue;
                }

                for (com.wc.fantasy.model.Player p : candidates) {
                    p.setPrice(price);
                    playerRepo.save(p);
                    updated++;
                }
            }
        } catch (Exception e) {
            log.error("Price upload failed", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to parse file: " + e.getMessage()));
        }

        return ResponseEntity.ok(Map.of("updated", updated, "skipped", skipped, "notFound", notFound, "errors", errors));
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

    // ── Squad position-mismatch audit ─────────────────────────────────────────

    /**
     * Returns every player placed in a slot whose position differs from their
     * actual position (e.g. a FWD sitting in a DEF slot).
     *
     * Slot positions are inferred from the team's formation:
     *   slot 0        → GK
     *   slots 1..defCount → DEF
     *   slots ..midCount  → MID
     *   slots ..fwdCount  → FWD
     * The same logic applies to the 4-player bench (GK, DEF, MID, FWD order).
     *
     * Response: list of { userId, username, displayName, teamId, slot, slotPosition, player, playerPosition }
     */
    @GetMapping("/squad-audit")
    public List<Map<String, Object>> auditSquadPositions() {
        List<UserTeam> allTeams = userTeamRepo.findAll();
        List<Map<String, Object>> mismatches = new ArrayList<>();

        for (UserTeam team : allTeams) {
            String formation = team.getFormation() != null ? team.getFormation() : "4-4-2";
            int[] parts = parseFormation(formation); // [def, mid, fwd]

            List<String> starterSlots = buildSlotPositions(parts);   // 11 entries
            List<String> benchSlots   = List.of("GK", "DEF", "MID", "FWD"); // always fixed

            checkSlots(team, team.getStarters(), starterSlots, "STARTER", mismatches);
            checkSlots(team, team.getBench(),    benchSlots,   "BENCH",   mismatches);
        }

        return mismatches;
    }

    private int[] parseFormation(String formation) {
        // formation like "4-4-2" → [4, 4, 2]
        String[] parts = formation.split("-");
        if (parts.length == 3) {
            try {
                return new int[]{ Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]) };
            } catch (NumberFormatException ignored) {}
        }
        return new int[]{ 4, 4, 2 }; // fallback
    }

    private List<String> buildSlotPositions(int[] parts) {
        List<String> slots = new ArrayList<>();
        slots.add("GK");
        for (int i = 0; i < parts[0]; i++) slots.add("DEF");
        for (int i = 0; i < parts[1]; i++) slots.add("MID");
        for (int i = 0; i < parts[2]; i++) slots.add("FWD");
        return slots;
    }

    private void checkSlots(UserTeam team, List<Player> players, List<String> slotPositions,
                            String section, List<Map<String, Object>> out) {
        for (int i = 0; i < players.size() && i < slotPositions.size(); i++) {
            Player p = players.get(i);
            String slotPos   = slotPositions.get(i);
            String playerPos = p.getPosition();
            if (!slotPos.equalsIgnoreCase(playerPos)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("userId",         team.getUser().getId());
                row.put("username",       team.getUser().getUsername());
                row.put("displayName",    team.getUser().getDisplayName());
                row.put("teamId",         team.getId());
                row.put("formation",      team.getFormation());
                row.put("section",        section);
                row.put("slotIndex",      i);
                row.put("slotPosition",   slotPos);
                row.put("playerId",       p.getId());
                row.put("playerName",     p.getName());
                row.put("playerPosition", playerPos);
                out.add(row);
            }
        }
    }

}
