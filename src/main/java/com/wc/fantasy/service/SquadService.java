package com.wc.fantasy.service;

import com.wc.fantasy.model.*;
import com.wc.fantasy.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SquadService {

    private final UserSquadRepository squadRepo;
    private final MatchRepository matchRepo;
    private final PlayerRepository playerRepo;
    private final MatchPlayerStatsRepository statsRepo;
    private final UserRepository userRepo;

    @Transactional
    public UserSquad saveSquad(Long userId, Long matchId, List<Long> playerIds, Long captainId) {
        Match match = matchRepo.findById(matchId).orElseThrow();
        if (match.getMatchTime().isBefore(LocalDateTime.now())) {
            throw new IllegalStateException("Match already started, cannot modify squad");
        }
        if (playerIds.size() != 11) {
            throw new IllegalArgumentException("Must select exactly 11 players");
        }
        AppUser user = userRepo.findById(userId).orElseThrow();
        List<Player> players = playerRepo.findAllById(playerIds);
        Player captain = playerRepo.findById(captainId).orElseThrow();

        UserSquad squad = squadRepo.findByUserIdAndMatchId(userId, matchId)
                .orElse(new UserSquad());
        squad.setUser(user);
        squad.setMatch(match);
        squad.setPlayers(players);
        squad.setCaptain(captain);
        squad.setLocked(false);
        return squadRepo.save(squad);
    }

    @Transactional
    public void calculatePoints(Long matchId) {
        List<UserSquad> squads = squadRepo.findAll().stream()
                .filter(s -> s.getMatch().getId().equals(matchId))
                .toList();

        for (UserSquad squad : squads) {
            // Subtract old points before recalculating
            int oldPoints = squad.getPointsEarned() != null ? squad.getPointsEarned() : 0;

            List<Long> playerIds = squad.getPlayers().stream().map(Player::getId).toList();
            List<MatchPlayerStats> stats = statsRepo.findByMatchIdAndPlayerIdIn(matchId, playerIds);

            int total = 0;
            for (MatchPlayerStats stat : stats) {
                int pts = computePlayerPoints(stat);
                if (stat.getPlayer().getId().equals(squad.getCaptain().getId())) {
                    pts *= 2; // captain gets double points
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

    private int computePlayerPoints(MatchPlayerStats s) {
        int pts = 0;
        pts += s.getGoals() * 6;
        pts += s.getAssists() * 4;
        if (Boolean.TRUE.equals(s.getCleanSheet())) pts += 4;
        pts -= s.getYellowCards();
        pts -= s.getRedCards() * 3;
        if (Boolean.TRUE.equals(s.getManOfMatch())) pts += 3;
        if (s.getMinutesPlayed() > 0) pts += 1; // appearance
        return pts;
    }

    public UserSquad getSquad(Long userId, Long matchId) {
        return squadRepo.findByUserIdAndMatchId(userId, matchId).orElse(null);
    }

    public List<UserSquad> getUserSquads(Long userId) {
        return squadRepo.findByUserId(userId);
    }
}
