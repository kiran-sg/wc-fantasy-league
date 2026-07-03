package com.wc.fantasy.service;

import com.wc.fantasy.model.AppUser;
import com.wc.fantasy.model.LeaderboardEntry;
import com.wc.fantasy.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final UserRepository userRepo;

    public List<LeaderboardEntry> getOverallLeaderboard() {
        List<AppUser> users = userRepo.findLeaderboardWithPoints();
        List<LeaderboardEntry> entries = new ArrayList<>(users.size());
        int rank = 1;
        for (int i = 0; i < users.size(); i++) {
            if (i > 0 && users.get(i).getTotalPoints() < users.get(i - 1).getTotalPoints()) {
                rank = i + 1;
            }
            entries.add(new LeaderboardEntry(rank, users.get(i)));
        }
        return entries;
    }
}
