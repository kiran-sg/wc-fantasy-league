package com.wc.fantasy.service;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;
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

    private static final BigDecimal BUDGET = BigDecimal.valueOf(105_000_000);

    private static final int UNLIMITED = Integer.MAX_VALUE;

    private static final Map<String, Integer> FREE_TRANSFERS = Map.of(
            "GROUP", UNLIMITED,
            "R32",   UNLIMITED,
            "R16",   4,
            "QF",    4,
            "SF",    5,
            "FINAL", 6
    );

    private static final Map<String, Integer> COUNTRY_LIMIT = Map.of(
            "GROUP", 3,
            "R32",   3,
            "R16",   4,
            "QF",    5,
            "SF",    6,
            "FINAL", 8
    );

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
                              String stage) {

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

        validatePositionQuota(starters, bench);
        validateBudget(all);
        validateCountryLimit(starters, stage);

        Player captain    = playerRepo.findById(captainId).orElseThrow();
        Player viceCaptain = playerRepo.findById(viceCaptainId).orElseThrow();

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
            UserTransferRecord record = transferRecordRepo
                    .findByUserIdAndStage(userId, stage)
                    .orElseGet(() -> {
                        UserTransferRecord r = new UserTransferRecord();
                        r.setUser(user);
                        r.setStage(stage);
                        return r;
                    });

            log.info("Transfer save: userId={} stage={} newTransfers={} oldSquad={} newAll={}",
                    userId, stage, newTransfers, oldSquadIds, allNewIds);

            int free = FREE_TRANSFERS.getOrDefault(stage, 2);
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
            }

            old.setStarters(orderedList(starters, starterIds));
            old.setBench(orderedList(bench, benchIds));
            old.setCaptain(captain);
            old.setViceCaptain(viceCaptain);
            old.setStage(stage);
            return teamRepo.save(old);
        }

        UserTeam team = new UserTeam();
        team.setUser(user);
        team.setStage(stage);
        team.setStarters(orderedList(starters, starterIds));
        team.setBench(orderedList(bench, benchIds));
        team.setCaptain(captain);
        team.setViceCaptain(viceCaptain);
        return teamRepo.save(team);
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
                } else if (isVC && captainDNP && !Boolean.TRUE.equals(team.getManualChangesMade())) {
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

        // Reset manualChangesMade after round completes
        for (UserTeam team : teams) {
            team.setManualChangesMade(false);
            teamRepo.save(team);
        }
    }

    // ── Auto-sub: replace DNP starters with bench in order ───────────────────

    private List<Player> resolveEffectiveXI(UserTeam team, Map<Long, MatchPlayerStats> statsMap) {
        if (Boolean.TRUE.equals(team.getManualChangesMade())) {
            return team.getStarters();
        }
        List<Player> xi = new ArrayList<>(team.getStarters());
        List<Player> bench = new ArrayList<>(team.getBench());

        for (int i = 0; i < xi.size(); i++) {
            Player starter = xi.get(i);
            MatchPlayerStats stat = statsMap.get(starter.getId());
            boolean dnp = stat == null || stat.getMinutesPlayed() == 0;
            if (dnp && !bench.isEmpty()) {
                xi.set(i, bench.remove(0));
            }
        }
        return xi;
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

    private void validatePositionQuota(List<Player> starters, List<Player> bench) {
        List<Player> all = new ArrayList<>(starters);
        all.addAll(bench);

        long gk  = all.stream().filter(p -> "GK".equals(p.getPosition())).count();
        long def = all.stream().filter(p -> "DEF".equals(p.getPosition())).count();
        long mid = all.stream().filter(p -> "MID".equals(p.getPosition())).count();
        long fwd = all.stream().filter(p -> "FWD".equals(p.getPosition())).count();

        if (gk != 2)  throw new IllegalArgumentException("Squad must have exactly 2 GKs (got " + gk + ")");
        if (def != 5) throw new IllegalArgumentException("Squad must have exactly 5 DEFs (got " + def + ")");
        if (mid != 5) throw new IllegalArgumentException("Squad must have exactly 5 MIDs (got " + mid + ")");
        if (fwd != 3) throw new IllegalArgumentException("Squad must have exactly 3 FWDs (got " + fwd + ")");

        // Starting XI formation constraints
        long startGk  = starters.stream().filter(p -> "GK".equals(p.getPosition())).count();
        long startDef = starters.stream().filter(p -> "DEF".equals(p.getPosition())).count();
        long startMid = starters.stream().filter(p -> "MID".equals(p.getPosition())).count();
        long startFwd = starters.stream().filter(p -> "FWD".equals(p.getPosition())).count();

        if (startGk < 1)  throw new IllegalArgumentException("Starting XI must have at least 1 GK");
        if (startDef < 3) throw new IllegalArgumentException("Starting XI must have at least 3 DEFs");
        if (startMid < 2) throw new IllegalArgumentException("Starting XI must have at least 2 MIDs");
        if (startFwd < 1) throw new IllegalArgumentException("Starting XI must have at least 1 FWD");
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

    private void validateCountryLimit(List<Player> starters, String stage) {
        int limit = COUNTRY_LIMIT.getOrDefault(stage, 3);
        long uniqueTeams = starters.stream().map(p -> p.getTeam().getId()).distinct().count();
        if (uniqueTeams == 0) return;
        int effectiveLimit = (int) Math.max(limit, Math.ceil(15.0 / uniqueTeams));

        Map<Long, Long> countPerTeam = starters.stream()
                .collect(Collectors.groupingBy(p -> p.getTeam().getId(), Collectors.counting()));
        countPerTeam.forEach((teamId, count) -> {
            if (count > effectiveLimit) {
                throw new IllegalArgumentException(
                        "Too many players from one country (max " + effectiveLimit + " for stage " + stage + ")");
            }
        });
    }

    // ── Match points for a user ───────────────────────────────────────────────

    public List<UserTeamMatchPoints> getMatchPoints(Long userId) {
        return teamRepo.findByUserId(userId)
                .map(team -> matchPointsRepo.findByUserTeamId(team.getId()))
                .orElse(Collections.emptyList());
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private List<Player> orderedList(List<Player> players, List<Long> orderedIds) {
        Map<Long, Player> map = players.stream().collect(Collectors.toMap(Player::getId, p -> p));
        return orderedIds.stream().map(map::get).filter(Objects::nonNull).collect(Collectors.toList());
    }
}
