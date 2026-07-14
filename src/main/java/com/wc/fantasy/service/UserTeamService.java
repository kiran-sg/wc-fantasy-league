package com.wc.fantasy.service;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserTeamService {

    private final UserTeamRepository teamRepo;
    private final UserTeamMatchPointsRepository matchPointsRepo;
    private final UserRepository userRepo;
    private final PlayerRepository playerRepo;
    private final MatchPlayerStatsRepository statsRepo;
    private final UserTransferRecordRepository transferRecordRepo;
    private final RoundConfigRepository roundConfigRepo;
    private final UserTeamSnapshotRepository snapshotRepo;
    private final MatchRepository matchRepo;

    private static final BigDecimal BUDGET = BigDecimal.valueOf(105_000_000);
    private static final int UNLIMITED = Integer.MAX_VALUE;

    // Fallback defaults used only when DB has no row for a stage
    private static final Map<String, Integer> DEFAULT_FREE_TRANSFERS = Map.of(
            "GROUP", UNLIMITED, "R32", 4, "R16", 4, "QF", 4, "SF", 5, "FINAL", 6);
    private static final Map<String, Integer> DEFAULT_COUNTRY_LIMIT = Map.of(
            "GROUP", 3, "R32", 3, "R16", 4, "QF", 5, "SF", 6, "FINAL", 8);

    // ── Active round resolution ───────────────────────────────────────────────

    public RoundConfig getActiveRoundConfig() {
        return roundConfigRepo.findActiveRound().orElse(null);
    }

    public String resolveActiveStage() {
        RoundConfig active = getActiveRoundConfig();
        return active != null ? active.getStage() : null;
    }

    private RoundConfig configFor(String stage) {
        return roundConfigRepo.findById(stage.toUpperCase()).orElse(null);
    }

    private int freeTransfersFor(String stage) {
        RoundConfig c = configFor(stage);
        return c != null ? c.getFreeTransfers() : DEFAULT_FREE_TRANSFERS.getOrDefault(stage, 2);
    }

    private int countryLimitFor(String stage) {
        RoundConfig c = configFor(stage);
        return c != null ? c.getCountryLimit() : DEFAULT_COUNTRY_LIMIT.getOrDefault(stage, 3);
    }

    /**
     * Window logic (all times in round's configured timezone):
     *
     * Case D — current round isRoundClosed=true → CLOSED
     * Case A — fifaRoundStart > now AND previous round isRoundClosed=false → CLOSED (prev round unsettled)
     * Case B — fifaRoundStart > now AND previous round isRoundClosed=true  → open immediately, close at windowCloseHour on lockDay
     * Case C — fifaRoundStart <= now AND current isRoundClosed=false        → open at windowOpenHour, close at windowCloseHour on lockDay
     *
     * lockDay = day before next upcoming match if match starts before windowCloseHour, else same day as match.
     */
    private void assertTransferWindowOpen(String stage) {
        WindowStatus status = computeWindowStatus(stage);
        if (!status.isOpen()) {
            throw new IllegalStateException(status.message());
        }
    }

    public WindowStatus computeWindowStatus(String stage) {
        RoundConfig c = configFor(stage);
        if (c == null) return WindowStatus.open("No config — window unrestricted");

        ZoneId tz = ZoneId.of(c.getWindowTimezone());
        ZonedDateTime now = ZonedDateTime.now(tz);

        // Case D — current round is closed by admin
        if (Boolean.TRUE.equals(c.getIsRoundClosed())) {
            return WindowStatus.closed("Transfer window is closed. Round " + stage + " has been settled.");
        }

        // Find next upcoming non-GROUP match
        List<Match> upcoming = matchRepo.findByStatusOrderByMatchTimeAsc("UPCOMING").stream()
                .filter(m -> !"GROUP".equals(m.getStage()))
                .toList();
        if (upcoming.isEmpty()) return WindowStatus.open("No upcoming matches — window open");

        ZonedDateTime nextMatchTime = upcoming.get(0).getMatchTime().atZone(tz);
        LocalDate lockDay = nextMatchTime.getHour() < c.getWindowCloseHour()
                ? nextMatchTime.toLocalDate().minusDays(1)
                : nextMatchTime.toLocalDate();

        String lockMsg = "Closes " + lockDay + " at " + c.getWindowCloseHour() + ":00 " + c.getWindowTimezone();

        // Determine case based on fifaRoundStart
        LocalDateTime fifaStart = c.getFifaRoundStart();
        boolean fifaNotYetStarted = fifaStart == null || now.toLocalDateTime().isBefore(fifaStart);

        if (fifaNotYetStarted) {
            // Case A or B — depends on previous round's isRoundClosed
            Optional<RoundConfig> prev = roundConfigRepo.findPreviousRound(stage);
            boolean prevClosed = prev.map(p -> Boolean.TRUE.equals(p.getIsRoundClosed())).orElse(true);

            if (!prevClosed) {
                // Case A — previous round not settled yet
                return WindowStatus.closed(
                        "Transfer window not yet open. Previous round (" + prev.map(RoundConfig::getStage).orElse("?") + ") is not settled yet.");
            }

            // Case B — previous round settled, open immediately until windowCloseHour on lockDay
            return checkCloseHourOnly(now, lockDay, c, tz, lockMsg);
        }

        // Case C — FIFA round has started, current round not closed
        return checkOpenAndCloseHour(now, lockDay, c, tz, lockMsg);
    }

    // Case B: no open-hour restriction — only check we're before lockDay closeHour
    private WindowStatus checkCloseHourOnly(ZonedDateTime now, LocalDate lockDay, RoundConfig c, ZoneId tz, String lockMsg) {
        LocalDate today = now.toLocalDate();
        if (today.isBefore(lockDay)) return WindowStatus.open(lockMsg);
        if (today.isEqual(lockDay)) {
            if (now.getHour() < c.getWindowCloseHour()) return WindowStatus.open(lockMsg);
        }
        return WindowStatus.closed("Transfer window is closed. Locked after " + lockDay + " " + c.getWindowCloseHour() + ":00 " + c.getWindowTimezone() + ".");
    }

    // Case C: must be on lockDay within [windowOpenHour, windowCloseHour), or before lockDay
    private WindowStatus checkOpenAndCloseHour(ZonedDateTime now, LocalDate lockDay, RoundConfig c, ZoneId tz, String lockMsg) {
        LocalDate today = now.toLocalDate();
        if (today.isBefore(lockDay)) return WindowStatus.open(lockMsg);
        if (today.isEqual(lockDay)) {
            int hour = now.getHour();
            if (hour >= c.getWindowOpenHour() && hour < c.getWindowCloseHour()) return WindowStatus.open(lockMsg);
            if (hour < c.getWindowOpenHour()) return WindowStatus.closed(
                    "Transfer window hasn't opened yet today. Opens at " + c.getWindowOpenHour() + ":00 " + c.getWindowTimezone() + ".");
        }
        return WindowStatus.closed("Transfer window is closed. Locked after " + lockDay + " " + c.getWindowCloseHour() + ":00 " + c.getWindowTimezone() + ".");
    }

    public record WindowStatus(boolean isOpen, String message) {
        static WindowStatus open(String msg)   { return new WindowStatus(true,  msg); }
        static WindowStatus closed(String msg) { return new WindowStatus(false, msg); }
    }

    // ── Get team ──────────────────────────────────────────────────────────────

    public UserTeam getTeam(Long userId) {
        return teamRepo.findByUserId(userId).orElse(null);
    }

    // ── Save / update team ────────────────────────────────────────────────────

    @Transactional
    public UserTeam saveTeam(Long userId,
                              List<Long> starterIds,     // 11
                              List<Long> benchIds,       // 4
                              Long captainId,
                              Long viceCaptainId,
                              String stageHint,          // client hint — used only if no roundStart configured
                              String formation) {        // e.g. "4-4-2"

        // Derive the authoritative stage from round_config.roundStart.
        // Fall back to the client-supplied hint only when no round has a roundStart set yet.
        String stage = resolveActiveStage();
        if (stage == null) stage = stageHint != null ? stageHint : "R32";
        log.info("saveTeam: userId={} resolvedStage={} clientHint={}", userId, stage, stageHint);

        assertTransferWindowOpen(stage);

        if (starterIds.size() != 11)
            throw new IllegalArgumentException("Must have exactly 11 starters");
        if (benchIds.size() != 4)
            throw new IllegalArgumentException("Must have exactly 4 bench players");

        List<Long> allIds = new ArrayList<>(starterIds);
        allIds.addAll(benchIds);
        if (new HashSet<>(allIds).size() != 15)
            throw new IllegalArgumentException("All 15 players must be unique");

        List<Player> starters = playerRepo.findAllById(starterIds);
        List<Player> bench    = playerRepo.findAllById(benchIds);
        List<Player> all      = new ArrayList<>(starters);
        all.addAll(bench);

        validatePositionQuota(starters, bench, formation);
        validateBudget(all);
        validateCountryLimit(starters, bench, stage);

        // Auto-assign captain = most expensive starter, VC = second most expensive, if not supplied
        List<Player> sortedByPrice = starters.stream()
                .filter(p -> p.getPrice() != null)
                .sorted((a, b) -> b.getPrice().compareTo(a.getPrice()))
                .toList();
        Player captain = (captainId != null)
                ? playerRepo.findById(captainId).orElse(sortedByPrice.isEmpty() ? starters.get(0) : sortedByPrice.get(0))
                : (sortedByPrice.isEmpty() ? starters.get(0) : sortedByPrice.get(0));
        Player viceCaptain = (viceCaptainId != null)
                ? playerRepo.findById(viceCaptainId).orElse(sortedByPrice.size() > 1 ? sortedByPrice.get(1) : captain)
                : (sortedByPrice.size() > 1 ? sortedByPrice.get(1) : captain);

        AppUser user = userRepo.findById(userId).orElseThrow();
        Optional<UserTeam> existing = teamRepo.findByUserId(userId);

        int penalty = 0;
        if (existing.isPresent()) {
            UserTeam old = existing.get();

            // Count any player in the new 15 that wasn't in the old 15 (starters + bench)
            Set<Long> oldSquadIds = new HashSet<>();
            old.getStarters().forEach(p -> oldSquadIds.add(p.getId()));
            old.getBench().forEach(p -> oldSquadIds.add(p.getId()));
            List<Long> allNewIds = new ArrayList<>(starterIds);
            allNewIds.addAll(benchIds);
            int newTransfers = (int) allNewIds.stream()
                    .filter(id -> !oldSquadIds.contains(id)).count();

            // Load or create the per-stage transfer record
            final String stageFinal = stage;
            UserTransferRecord record = transferRecordRepo
                    .findByUserIdAndStage(userId, stageFinal)
                    .orElseGet(() -> {
                        UserTransferRecord r = new UserTransferRecord();
                        r.setUser(user);
                        r.setStage(stageFinal);
                        return r;
                    });

            log.info("Transfer save: userId={} stage={} newTransfers={} oldSquad={} newAll={}",
                    userId, stage, newTransfers, oldSquadIds, allNewIds);

            int free = freeTransfersFor(stage);
            int previouslyMade = record.getTransfersMade() != null ? record.getTransfersMade() : 0;
            int previousPenalty = record.getPenaltyPoints() != null ? record.getPenaltyPoints() : 0;
            int totalThisStage = previouslyMade + newTransfers;
            // Extra transfers = amount that exceeds free allowance, above what was already penalised
            int alreadyPenalised = Math.max(0, previouslyMade - free);
            int nowPenalised     = Math.max(0, totalThisStage - free);
            int extraNow         = nowPenalised - alreadyPenalised;
            penalty = extraNow * 3;

            record.setTransfersMade(totalThisStage);
            record.setPenaltyPoints(previousPenalty + penalty);
            transferRecordRepo.save(record);

            if (penalty > 0) {
                user.setTotalPoints(Math.max(0, user.getTotalPoints() - penalty));
                userRepo.save(user);
                // Keep UserTeam.penaltyPoints in sync so calculatePoints re-deducts correctly
                int currentTeamPenalty = old.getPenaltyPoints() != null ? old.getPenaltyPoints() : 0;
                old.setPenaltyPoints(currentTeamPenalty + penalty);
            }

            old.setStarters(orderedList(starters, starterIds));
            old.setBench(orderedList(bench, benchIds));
            old.setCaptain(captain);
            old.setViceCaptain(viceCaptain);
            old.setStage(stage);
            if (formation != null && !formation.isBlank()) old.setFormation(formation);
            UserTeam saved = teamRepo.save(old);
            captureSnapshot(user, saved, stage);
            return saved;
        }

        UserTeam team = new UserTeam();
        team.setUser(user);
        team.setStage(stage);
        team.setFormation(formation != null && !formation.isBlank() ? formation : "4-4-2");
        team.setStarters(orderedList(starters, starterIds));
        team.setBench(orderedList(bench, benchIds));
        team.setCaptain(captain);
        team.setViceCaptain(viceCaptain);
        UserTeam saved = teamRepo.save(team);
        captureSnapshot(user, saved, stage);
        return saved;
    }

    private void captureSnapshot(AppUser user, UserTeam team, String stage) {
        if (snapshotRepo.findByUserIdAndStage(user.getId(), stage).isPresent()) return;
        UserTeamSnapshot snap = new UserTeamSnapshot();
        snap.setUser(user);
        snap.setStage(stage);
        snap.setFormation(team.getFormation());
        snap.setStarters(new ArrayList<>(team.getStarters()));
        snap.setBench(new ArrayList<>(team.getBench()));
        snap.setCaptain(team.getCaptain());
        snap.setViceCaptain(team.getViceCaptain());
        snapshotRepo.save(snap);
        log.info("Snapshot captured: userId={} stage={}", user.getId(), stage);
    }

    // ── Calculate points for all teams after a match ──────────────────────────

    @Transactional
    public void calculatePointsForMatch(Long matchId, Match match) {
        List<MatchPlayerStats> allStats = statsRepo.findByMatchId(matchId);
        Map<Long, MatchPlayerStats> statsMap = allStats.stream()
                .collect(Collectors.toMap(s -> s.getPlayer().getId(), s -> s));

        // Persist base points on each stat record
        for (MatchPlayerStats stat : allStats) {
            stat.setTotalPoints(computePlayerPoints(stat));
        }
        statsRepo.saveAll(allStats);

        List<UserTeam> teams = teamRepo.findAll();
        for (UserTeam team : teams) {
            List<Player> effectiveXI = resolveEffectiveXI(team, statsMap);

            int total = 0;
            for (Player player : effectiveXI) {
                MatchPlayerStats stat = statsMap.get(player.getId());
                if (stat == null) continue;

                int pts = stat.getTotalPoints() != null ? stat.getTotalPoints() : 0;

                boolean isCaptain = player.getId().equals(team.getCaptain() != null ? team.getCaptain().getId() : -1L);
                boolean isVC = team.getViceCaptain() != null && player.getId().equals(team.getViceCaptain().getId());

                MatchPlayerStats captainStat = team.getCaptain() != null ? statsMap.get(team.getCaptain().getId()) : null;
                boolean captainDNP = captainStat == null || captainStat.getMinutesPlayed() == 0;

                if (isCaptain) {
                    pts *= 2;
                } else if (isVC && captainDNP) {
                    pts *= 2;
                }

                total += pts;
            }

            // Upsert match points record
            Optional<UserTeamMatchPoints> existingRecord =
                    matchPointsRepo.findByUserTeamIdAndMatchId(team.getId(), matchId);
            UserTeamMatchPoints record = existingRecord.orElse(new UserTeamMatchPoints());
            record.setUserTeam(team);
            record.setMatch(match);
            record.setStage(match.getStage());
            record.setPointsEarned(total);
            matchPointsRepo.save(record);

            // Recompute total across all matches
            int newTotal = matchPointsRepo.findByUserTeamId(team.getId()).stream()
                    .mapToInt(p -> p.getPointsEarned() != null ? p.getPointsEarned() : 0)
                    .sum();
            // Subtract penalty points that were already deducted from totalPoints
            AppUser user = team.getUser();
            user.setTotalPoints(newTotal - (team.getPenaltyPoints() != null ? team.getPenaltyPoints() : 0));
            userRepo.save(user);
        }

    }

    // Points are calculated from the fixed 11 starters only — no auto-sub
    private List<Player> resolveEffectiveXI(UserTeam team, Map<Long, MatchPlayerStats> statsMap) {
        return team.getStarters();
    }

    // ── Points formula ────────────────────────────────────────────────────────

    public int computePlayerPoints(MatchPlayerStats s) {
        if (s.getMinutesPlayed() == null || s.getMinutesPlayed() == 0) return 0;

        int pts = 0;
        String pos = s.getPlayer().getPosition();
        int mins = s.getMinutesPlayed();

        // Appearance
        pts += (mins >= 60) ? 2 : 1;

        // Goals — position-aware
        int goals = s.getGoals() != null ? s.getGoals() : 0;
        pts += goals * goalPoints(pos);

        // Assists
        pts += (s.getAssists() != null ? s.getAssists() : 0) * 3;

        // Clean sheet — only if player played 60+ minutes
        if (Boolean.TRUE.equals(s.getCleanSheet()) && mins >= 60) {
            if ("GK".equals(pos) || "DEF".equals(pos)) pts += 5;
            else if ("MID".equals(pos)) pts += 1;
        }

        // Goals conceded (GK and DEF) — first goal is 0, each additional is -1
        if ("GK".equals(pos) || "DEF".equals(pos)) {
            int conceded = s.getGoalsConceded() != null ? s.getGoalsConceded() : 0;
            if (conceded > 1) pts -= (conceded - 1);
        }

        // Cards
        pts -= (s.getYellowCards() != null ? s.getYellowCards() : 0);
        pts -= (s.getRedCards() != null ? s.getRedCards() : 0) * 2;

        // Own goals
        pts -= (s.getOwnGoals() != null ? s.getOwnGoals() : 0) * 2;

        // GK: every 3 saves = +1
        if ("GK".equals(pos)) {
            int saves = s.getSaves() != null ? s.getSaves() : 0;
            pts += saves / 3;
        }

        // FWD: every 2 shots on target = +1
        if ("FWD".equals(pos)) {
            int sot = s.getShotsOnTarget() != null ? s.getShotsOnTarget() : 0;
            pts += sot / 2;
        }

        return pts;
    }

    private int goalPoints(String position) {
        return switch (position) {
            case "GK"  -> 9;
            case "DEF" -> 7;
            case "MID" -> 6;
            case "FWD" -> 5;
            default    -> 6;
        };
    }

    // ── Validation ────────────────────────────────────────────────────────────

    // formation → [DEF, MID, FWD] counts for the XI (GK is always 1 in XI)
    private static final java.util.Map<String, int[]> FORMATION_COUNTS = java.util.Map.of(
        "4-4-2", new int[]{4, 4, 2},
        "4-3-3", new int[]{4, 3, 3},
        "4-5-1", new int[]{4, 5, 1},
        "3-4-3", new int[]{3, 4, 3},
        "3-5-2", new int[]{3, 5, 2},
        "5-4-1", new int[]{5, 4, 1},
        "5-3-2", new int[]{5, 3, 2}
    );

    private void validatePositionQuota(List<Player> starters, List<Player> bench, String formation) {
        String f = (formation != null && FORMATION_COUNTS.containsKey(formation)) ? formation : "4-4-2";
        int[] counts = FORMATION_COUNTS.get(f); // [DEF, MID, FWD] for XI
        int xiDef = counts[0], xiMid = counts[1], xiFwd = counts[2];

        // Squad of 15: 2 GK, (xiDef+1) DEF, (xiMid+1) MID, (xiFwd+1) FWD
        List<Player> all = new ArrayList<>(starters);
        all.addAll(bench);

        long gk  = all.stream().filter(p -> "GK".equals(p.getPosition())).count();
        long def = all.stream().filter(p -> "DEF".equals(p.getPosition())).count();
        long mid = all.stream().filter(p -> "MID".equals(p.getPosition())).count();
        long fwd = all.stream().filter(p -> "FWD".equals(p.getPosition())).count();

        if (gk != 2)           throw new IllegalArgumentException("Squad must have exactly 2 GKs (got " + gk + ")");
        if (def != xiDef + 1)  throw new IllegalArgumentException("Formation " + f + " requires " + (xiDef+1) + " DEFs in squad (got " + def + ")");
        if (mid != xiMid + 1)  throw new IllegalArgumentException("Formation " + f + " requires " + (xiMid+1) + " MIDs in squad (got " + mid + ")");
        if (fwd != xiFwd + 1)  throw new IllegalArgumentException("Formation " + f + " requires " + (xiFwd+1) + " FWDs in squad (got " + fwd + ")");

        // Starting XI must exactly match the formation
        long startGk  = starters.stream().filter(p -> "GK".equals(p.getPosition())).count();
        long startDef = starters.stream().filter(p -> "DEF".equals(p.getPosition())).count();
        long startMid = starters.stream().filter(p -> "MID".equals(p.getPosition())).count();
        long startFwd = starters.stream().filter(p -> "FWD".equals(p.getPosition())).count();

        if (startGk != 1)      throw new IllegalArgumentException("Starting XI must have exactly 1 GK");
        if (startDef != xiDef) throw new IllegalArgumentException("Formation " + f + " requires " + xiDef + " DEFs in XI (got " + startDef + ")");
        if (startMid != xiMid) throw new IllegalArgumentException("Formation " + f + " requires " + xiMid + " MIDs in XI (got " + startMid + ")");
        if (startFwd != xiFwd) throw new IllegalArgumentException("Formation " + f + " requires " + xiFwd + " FWDs in XI (got " + startFwd + ")");
    }

    private void validateBudget(List<Player> players) {
        BigDecimal total = players.stream()
                .map(p -> p.getPrice() != null ? p.getPrice() : BigDecimal.valueOf(6_000_000))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BUDGET) > 0) {
            throw new IllegalArgumentException(
                    "Squad cost exceeds $105,000,000 budget (currently $" + total.toPlainString() + ")");
        }
    }

    private void validateCountryLimit(List<Player> starters, List<Player> bench, String stage) {
        int limit = countryLimitFor(stage);
        List<Player> all = new ArrayList<>(starters);
        all.addAll(bench);
        Map<Long, Long> countPerTeam = all.stream()
                .collect(Collectors.groupingBy(p -> p.getTeam().getId(), Collectors.counting()));
        countPerTeam.forEach((teamId, count) -> {
            if (count > limit) {
                throw new IllegalArgumentException(
                        "Too many players from one country (max " + limit + " in full squad for stage " + stage + ")");
            }
        });
    }

    // ── Match points for a user ───────────────────────────────────────────────

    public List<UserTeamMatchPoints> getMatchPoints(Long userId) {
        return teamRepo.findByUserId(userId)
                .map(team -> matchPointsRepo.findByUserTeamId(team.getId()))
                .orElse(Collections.emptyList());
    }

    // ── Country limit audit ───────────────────────────────────────────────────

    public List<Map<String, Object>> auditCountryLimits() {
        String stage = resolveActiveStage();
        if (stage == null) stage = "GROUP";
        int limit = countryLimitFor(stage);

        List<Map<String, Object>> violations = new ArrayList<>();
        for (UserTeam team : teamRepo.findAll()) {
            List<Player> all = new ArrayList<>(team.getStarters());
            all.addAll(team.getBench());

            Map<Long, List<Player>> byCountry = new java.util.LinkedHashMap<>();
            for (Player p : all) {
                byCountry.computeIfAbsent(p.getTeam().getId(), k -> new ArrayList<>()).add(p);
            }

            List<Map<String, Object>> teamViolations = new ArrayList<>();
            Set<Long> starterIds = team.getStarters().stream()
                    .map(Player::getId).collect(Collectors.toSet());

            for (Map.Entry<Long, List<Player>> entry : byCountry.entrySet()) {
                if (entry.getValue().size() > limit) {
                    List<Map<String, Object>> players = entry.getValue().stream().map(p -> {
                        Map<String, Object> pm = new java.util.LinkedHashMap<>();
                        pm.put("id",       p.getId());
                        pm.put("name",     p.getName());
                        pm.put("position", p.getPosition());
                        pm.put("section",  starterIds.contains(p.getId()) ? "STARTER" : "BENCH");
                        return pm;
                    }).collect(Collectors.toList());

                    Map<String, Object> v = new java.util.LinkedHashMap<>();
                    v.put("countryId",   entry.getKey());
                    v.put("countryName", entry.getValue().get(0).getTeam().getName());
                    v.put("count",       entry.getValue().size());
                    v.put("limit",       limit);
                    v.put("players",     players);
                    teamViolations.add(v);
                }
            }

            if (!teamViolations.isEmpty()) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("userId",      team.getUser().getId());
                row.put("username",    team.getUser().getUsername());
                row.put("displayName", team.getUser().getDisplayName());
                row.put("stage",       stage);
                row.put("violations",  teamViolations);
                violations.add(row);
            }
        }
        return violations;
    }

    // ── Transfer record for a user + stage ───────────────────────────────────

    public UserTransferRecord getTransferRecord(Long userId, String stage) {
        return transferRecordRepo.findByUserIdAndStage(userId, stage).orElseGet(() -> {
            UserTransferRecord r = new UserTransferRecord();
            r.setStage(stage);
            r.setTransfersMade(0);
            r.setPenaltyPoints(0);
            return r;
        });
    }

    public List<UserTransferRecord> getAllTransferRecords(Long userId) {
        return transferRecordRepo.findByUserId(userId);
    }

    @Transactional
    public List<UserTeamSnapshot> getSnapshots(Long userId) {
        // Backfill: if the user has a saved team but no snapshot for its stage, create one now
        teamRepo.findByUserId(userId).ifPresent(team -> {
            String stage = team.getStage();
            if (stage != null && snapshotRepo.findByUserIdAndStage(userId, stage).isEmpty()) {
                captureSnapshot(team.getUser(), team, stage);
            }
        });
        return snapshotRepo.findByUserIdOrderByStageAsc(userId);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Player> orderedList(List<Player> players, List<Long> orderedIds) {
        Map<Long, Player> map = players.stream().collect(Collectors.toMap(Player::getId, p -> p));
        return orderedIds.stream().map(map::get).filter(Objects::nonNull).collect(Collectors.toList());
    }
}
