package com.wc.fantasy.service;

import com.wc.fantasy.model.AppUser;
import com.wc.fantasy.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final UserRepository userRepo;

    public List<AppUser> getLeaderboard() {
        return userRepo.findAll(Sort.by(Sort.Direction.DESC, "totalPoints"));
    }
}
