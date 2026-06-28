package com.wc.fantasy.service;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SquadService {

    private final UserSquadRepository squadRepo;
    private final MatchRepository matchRepo;
    private final PlayerRepository playerRepo;
    private final MatchPlayerStatsRepository statsRepo;
    private final UserRepository userRepo;

    // Free transfer quota per stage
    private static final BigDecimal BUDGET = BigDecimal.valueOf(105_000_000);

    private static final Map<String, Integer> FREE_TRANSFERS = Map.of(
            "R32",   4,
            "R16",   4,
            "QF",    4,
            "SF",    5,
            "FINAL", 6
    );

    // Max players per country per stage
    private static final Map<String, Integer> COUNTRY_LIMIT = Map.of(
            "GROUP", 3,
            "R32",   3,
            "R16",   4,
            "QF",    5,
            "SF",    6,
            "FINAL", 8
    );

    // ── Save squad ────────────────────────────────────────────────────────────

    @Transactional
    public UserSquad saveSquad(Long userId, Long matchId,
                               List<Long> startingIds,   // 11 starters
                               Long captainId,
                               Long viceCaptainId,
                               List<Long> benchIds) {    // up to 4 bench players in priority order

        Match match = matchRepo.findById(matchId).orElseThrow();
        if (match.getMatchTime().isBefore(LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")))) {
            throw new IllegalStateException("Match already started, cannot modify squad");
        }

        List<Long> allIds = new ArrayList<>(startingIds);
        allIds.addAll(benchIds);

        if (startingIds.size() != 11) {
            throw new IllegalArgumentException("Must select exactly 11 starting players");
        }
        if (allIds.size() > 15) {
            throw new IllegalArgumentException("Squad cannot exceed 15 players");
        }

        List<Player> starters = playerRepo.findAllById(startingIds);
        List<Player> bench = benchIds.isEmpty() ? Collections.emptyList() : playerRepo.findAllById(benchIds);
        List<Player> allPlayers = new ArrayList<>(starters);
        allPlayers.addAll(bench);

        validateCountryLimit(starters, match.getStage());
        validatePositions(starters);
        validateBudget(allPlayers);

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

        // Transfer penalty: count previous squads for this user in this stage and deduct points
        AppUser user = userRepo.findById(userId).orElseThrow();
        Optional<UserSquad> existing = squadRepo.findByUserIdAndMatchId(userId, matchId);
        if (existing.isPresent()) {
            applyTransferPenalty(user, existing.get(), startingIds, match.getStage());
        }

        UserSquad squad = existing.orElse(new UserSquad());
        squad.setUser(user);
        squad.setMatch(match);
        squad.setPlayers(starters);
        squad.setBench(bench);
        squad.setCaptain(captain);
        squad.setViceCaptain(viceCaptain);
        squad.setLocked(false);
        squad.setManualChangesMade(false);
        return squadRepo.save(squad);
    }

    // ── Manual substitution (live round) ─────────────────────────────────────

    @Transactional
    public UserSquad manualSub(Long squadId, Long outPlayerId, Long inPlayerId) {
        UserSquad squad = squadRepo.findById(squadId).orElseThrow();
        Match match = squad.getMatch();

        if (!match.getStatus().equals("LIVE")) {
            throw new IllegalStateException("Manual substitutions only allowed during a live match");
        }

        List<MatchPlayerStats> stats = statsRepo.findByMatchId(match.getId());
        Map<Long, MatchPlayerStats> statsMap = stats.stream()
                .collect(Collectors.toMap(s -> s.getPlayer().getId(), s -> s));

        // Starter being subbed out must not currently be playing (minutesPlayed not set to max yet)
        // Bench player coming in must not have played yet
        MatchPlayerStats inStats = statsMap.get(inPlayerId);
        if (inStats != null && inStats.getMinutesPlayed() > 0) {
            throw new IllegalStateException("Bench player has already played, cannot bring in");
        }

        List<Player> starters = new ArrayList<>(squad.getPlayers());
        List<Player> bench = new ArrayList<>(squad.getBench());

        Player out = starters.stream().filter(p -> p.getId().equals(outPlayerId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Player not in starting XI"));
        Player in = bench.stream().filter(p -> p.getId().equals(inPlayerId))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Player not on bench"));

        starters.remove(out);
        starters.add(in);
        bench.remove(in);
        bench.add(out);

        squad.setPlayers(starters);
        squad.setBench(bench);
        squad.setManualChangesMade(true);
        return squadRepo.save(squad);
    }

    // ── Calculate points ──────────────────────────────────────────────────────

    @Transactional
    public void calculatePoints(Long matchId) {
        List<UserSquad> squads = squadRepo.findByMatchId(matchId);
        List<MatchPlayerStats> allMatchStats = statsRepo.findByMatchId(matchId);
        Map<Long, MatchPlayerStats> statsMap = allMatchStats.stream()
                .collect(Collectors.toMap(s -> s.getPlayer().getId(), s -> s));

        // Persist per-player base points on the stat records
        for (MatchPlayerStats stat : allMatchStats) {
            stat.setTotalPoints(computePlayerPoints(stat));
        }
        statsRepo.saveAll(allMatchStats);

        for (UserSquad squad : squads) {
            // Auto-substitute DNP starters (only if no manual changes made)
            List<Player> effectiveXI = resolveEffectiveXI(squad, statsMap);

            int oldPoints = squad.getPointsEarned() != null ? squad.getPointsEarned() : 0;
            int total = 0;

            for (Player player : effectiveXI) {
                MatchPlayerStats stat = statsMap.get(player.getId());
                if (stat == null) continue;

                int pts = computePlayerPoints(stat);

                // Captain double — fall back to VC if captain did not play and no manual changes
                boolean isCaptain = player.getId().equals(squad.getCaptain().getId());
                boolean isVC = squad.getViceCaptain() != null && player.getId().equals(squad.getViceCaptain().getId());

                MatchPlayerStats captainStat = statsMap.get(squad.getCaptain().getId());
                boolean captainDidNotPlay = captainStat == null || captainStat.getMinutesPlayed() == 0;

                if (isCaptain) {
                    pts *= 2;
                } else if (isVC && captainDidNotPlay && !Boolean.TRUE.equals(squad.getManualChangesMade())) {
                    pts *= 2;
                }

                total += pts;
            }

            squad.setPointsEarned(total);
            squad.setLocked(true);
            squadRepo.save(squad);

            AppUser user = squad.getUser();
            user.setTotalPoints(user.getTotalPoints() - oldPoints + total);
            userRepo.save(user);
        }
    }

    // ── Auto-sub: replace DNP starters with bench in priority order ───────────

    private List<Player> resolveEffectiveXI(UserSquad squad, Map<Long, MatchPlayerStats> statsMap) {
        if (Boolean.TRUE.equals(squad.getManualChangesMade())) {
            return squad.getPlayers(); // manual changes cancel auto-subs
        }

        List<Player> xi = new ArrayList<>(squad.getPlayers());
        List<Player> bench = new ArrayList<>(squad.getBench()); // ordered by bench_order

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

    // ── Points formula (knockout rules) ──────────────────────────────────────

    public int computePlayerPoints(MatchPlayerStats s) {
        if (s.getMinutesPlayed() == 0) return 0; // DNP — no points, no penalties

        int pts = 0;
        String pos = s.getPlayer().getPosition();

        // Appearance
        if (s.getMinutesPlayed() >= 60) pts += 2;
        else pts += 1;

        // Goals — position-aware
        int goals = s.getGoals() != null ? s.getGoals() : 0;
        pts += goals * goalPoints(pos);

        // Assists
        pts += (s.getAssists() != null ? s.getAssists() : 0) * 3;

        // Clean sheet — requires 60+ minutes played
        if (Boolean.TRUE.equals(s.getCleanSheet()) && s.getMinutesPlayed() >= 60) {
            if ("GK".equals(pos) || "DEF".equals(pos)) pts += 5;
            else if ("MID".equals(pos)) pts += 1;
        }

        // Goals conceded (GK and DEF only) — first goal is 0, each additional is -1
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

    // ── Validation helpers ────────────────────────────────────────────────────

    private void validateBudget(List<Player> players) {
        BigDecimal total = players.stream()
                .map(p -> p.getPrice() != null ? p.getPrice() : BigDecimal.valueOf(6_000_000))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.compareTo(BUDGET) > 0) {
            throw new IllegalArgumentException(
                    "Squad cost $" + total.toPlainString() + " exceeds $105,000,000 budget");
        }
    }

    // ── Live captain change ───────────────────────────────────────────────────

    @Transactional
    public UserSquad changeCaptain(Long squadId, Long newCaptainId) {
        UserSquad squad = squadRepo.findById(squadId).orElseThrow();
        Match match = squad.getMatch();

        if (!match.getStatus().equals("LIVE")) {
            throw new IllegalStateException("Captain changes only allowed during a live match");
        }

        List<MatchPlayerStats> stats = statsRepo.findByMatchId(match.getId());
        Map<Long, MatchPlayerStats> statsMap = stats.stream()
                .collect(Collectors.toMap(s -> s.getPlayer().getId(), s -> s));

        // New captain must not have played yet
        MatchPlayerStats newCapStats = statsMap.get(newCaptainId);
        if (newCapStats != null && newCapStats.getMinutesPlayed() > 0) {
            throw new IllegalStateException("New captain has already played — cannot assign");
        }

        // Old captain must have finished playing
        MatchPlayerStats oldCapStats = statsMap.get(squad.getCaptain().getId());
        if (oldCapStats == null || oldCapStats.getMinutesPlayed() == 0) {
            throw new IllegalStateException("Current captain has not finished playing yet");
        }

        Player newCaptain = playerRepo.findById(newCaptainId).orElseThrow();
        squad.setCaptain(newCaptain);
        squad.setManualChangesMade(true);
        return squadRepo.save(squad);
    }

    private void validateCountryLimit(List<Player> starters, String stage) {
        int limit = COUNTRY_LIMIT.getOrDefault(stage, 3);
        Map<Long, Long> countPerTeam = starters.stream()
                .collect(Collectors.groupingBy(p -> p.getTeam().getId(), Collectors.counting()));
        countPerTeam.forEach((teamId, count) -> {
            if (count > limit) {
                throw new IllegalArgumentException(
                        "Too many players from the same country for stage " + stage + " (max " + limit + ")");
            }
        });
    }

    private void validatePositions(List<Player> starters) {
        long gk  = starters.stream().filter(p -> "GK".equals(p.getPosition())).count();
        long def = starters.stream().filter(p -> "DEF".equals(p.getPosition())).count();
        long mid = starters.stream().filter(p -> "MID".equals(p.getPosition())).count();
        long fwd = starters.stream().filter(p -> "FWD".equals(p.getPosition())).count();
        if (gk < 1) throw new IllegalArgumentException("Starting XI must include at least 1 GK");
        if (def < 3) throw new IllegalArgumentException("Starting XI must include at least 3 DEF");
        if (mid < 2) throw new IllegalArgumentException("Starting XI must include at least 2 MID");
        if (fwd < 1) throw new IllegalArgumentException("Starting XI must include at least 1 FWD");
    }

    private void applyTransferPenalty(AppUser user, UserSquad existing, List<Long> newStartingIds, String stage) {
        Set<Long> oldIds = existing.getPlayers().stream().map(Player::getId).collect(Collectors.toSet());
        long transfers = newStartingIds.stream().filter(id -> !oldIds.contains(id)).count();
        int freeAllowance = FREE_TRANSFERS.getOrDefault(stage, 2);
        int penalty = (int) Math.max(0, transfers - freeAllowance) * 3;
        if (penalty > 0) {
            user.setTotalPoints(user.getTotalPoints() - penalty);
            userRepo.save(user);
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    public UserSquad getSquad(Long userId, Long matchId) {
        return squadRepo.findByUserIdAndMatchId(userId, matchId).orElse(null);
    }

    public List<UserSquad> getUserSquads(Long userId) {
        return squadRepo.findByUserId(userId);
    }
}
