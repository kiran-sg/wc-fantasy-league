package com.wc.fantasy.controller;

import com.wc.fantasy.model.Match;
import com.wc.fantasy.repository.MatchRepository;
import com.wc.fantasy.service.MatchSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/matches")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class MatchController {

    private final MatchRepository matchRepo;
    private final MatchSyncService matchSyncService;

    @GetMapping
    public List<Match> getAll() {
        return matchRepo.findAll();
    }

    @GetMapping("/status/{status}")
    public List<Match> getByStatus(@PathVariable String status) {
        return matchRepo.findByStatusOrderByMatchTimeAsc(status);
    }

    @GetMapping("/{id}")
    public Match getById(@PathVariable Long id) {
        return matchRepo.findById(id).orElseThrow();
    }

    @PostMapping("/sync")
    public Map<String, String> syncFromPredictionApi() {
        matchSyncService.syncMatchesFromPredictionApi();
        return Map.of("status", "synced");
    }
}
