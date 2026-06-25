package com.wc.fantasy.service;

import com.wc.fantasy.model.AppUser;
import com.wc.fantasy.model.UserSquad;
import com.wc.fantasy.repository.UserRepository;
import com.wc.fantasy.repository.UserSquadRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final UserRepository userRepo;
    private final UserSquadRepository squadRepo;

    public List<AppUser> getOverallLeaderboard() {
        return userRepo.findByIsAdminFalseOrIsAdminIsNullOrderByTotalPointsDesc();
    }

    public List<Map<String, Object>> getRoundLeaderboard(Long matchId) {
        List<UserSquad> squads = squadRepo.findByMatchId(matchId);
        return squads.stream()
                .filter(s -> s.getUser() != null && (s.getUser().getIsAdmin() == null || !s.getUser().getIsAdmin()))
                .sorted(Comparator.comparingInt(
                        (UserSquad s) -> s.getPointsEarned() != null ? s.getPointsEarned() : 0)
                        .reversed())
                .map(s -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("userId", s.getUser().getId());
                    entry.put("username", s.getUser().getUsername());
                    entry.put("displayName", s.getUser().getDisplayName());
                    entry.put("roundPoints", s.getPointsEarned() != null ? s.getPointsEarned() : 0);
                    return entry;
                })
                .collect(Collectors.toList());
    }
}
