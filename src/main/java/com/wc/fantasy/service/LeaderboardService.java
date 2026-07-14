package com.wc.fantasy.service;

import com.wc.fantasy.model.AppUser;
import com.wc.fantasy.model.LeaderboardEntry;
import com.wc.fantasy.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final UserRepository userRepo;

    public List<LeaderboardEntry> getOverallLeaderboard() {
        List<AppUser> users = userRepo.findLeaderboardCandidates();
        List<LeaderboardEntry> entries = users.stream()
                .map(u -> new LeaderboardEntry(0, u))
                .sorted(Comparator.comparingInt(LeaderboardEntry::getFinalPoints).reversed())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        int rank = 1;
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0 && entries.get(i).getFinalPoints() < entries.get(i - 1).getFinalPoints()) {
                rank = i + 1;
            }
            entries.get(i).setRank(rank);
        }
        return entries;
    }
}
