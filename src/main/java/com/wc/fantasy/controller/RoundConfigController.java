package com.wc.fantasy.controller;

import com.wc.fantasy.model.RoundConfig;
import com.wc.fantasy.repository.RoundConfigRepository;
import com.wc.fantasy.service.DataSyncService;
import com.wc.fantasy.service.UserTeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/round-config")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class RoundConfigController {

    private final RoundConfigRepository repo;
    private final UserTeamService teamService;
    private final DataSyncService dataSyncService;

    // Public — all rows (for admin panel and my-team display)
    @GetMapping
    public List<RoundConfig> getAll() {
        return repo.findAll();
    }

    // Public — the currently active round (roundStart <= now, latest one)
    @GetMapping("/active")
    public ResponseEntity<?> getActive() {
        RoundConfig active = teamService.getActiveRoundConfig();
        if (active == null) return ResponseEntity.ok(Map.of("stage", "NONE", "message", "No round has started yet"));
        return ResponseEntity.ok(active);
    }

    // Public — current transfer window open/closed state with reason message
    @GetMapping("/window-status")
    public ResponseEntity<Map<String, Object>> getWindowStatus() {
        RoundConfig active = teamService.getActiveRoundConfig();
        String stage = active != null ? active.getStage() : "GROUP";
        UserTeamService.WindowStatus status = teamService.computeWindowStatus(stage);
        return ResponseEntity.ok(Map.of(
                "open",    status.isOpen(),
                "message", status.message(),
                "stage",   stage
        ));
    }

    // Admin — toggle isRoundClosed for a stage
    @PatchMapping("/{stage}/close")
    public ResponseEntity<?> setRoundClosed(@PathVariable String stage, @RequestBody Map<String, Object> body) {
        return repo.findById(stage.toUpperCase()).map(rc -> {
            rc.setIsRoundClosed(Boolean.parseBoolean(body.getOrDefault("isRoundClosed", "false").toString()));
            return ResponseEntity.ok(repo.save(rc));
        }).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/{stage}")
    public ResponseEntity<RoundConfig> getOne(@PathVariable String stage) {
        return repo.findById(stage.toUpperCase())
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    // Admin — re-derive all roundStart values from the earliest match of each stage
    @PostMapping("/sync-starts")
    public List<RoundConfig> syncStarts() {
        // Clear all existing roundStart values so refreshRoundStarts() will re-fill them
        for (RoundConfig rc : repo.findAll()) {
            rc.setRoundStart(null);
            repo.save(rc);
        }
        dataSyncService.refreshRoundStarts();
        return repo.findAll();
    }

    // Admin — update a round's rules + roundStart + fifaRoundStart without redeploying
    @PutMapping("/{stage}")
    public ResponseEntity<RoundConfig> update(@PathVariable String stage, @RequestBody RoundConfig incoming) {
        return repo.findById(stage.toUpperCase()).map(existing -> {
            existing.setFreeTransfers(incoming.getFreeTransfers());
            existing.setCountryLimit(incoming.getCountryLimit());
            existing.setWindowOpenHour(incoming.getWindowOpenHour());
            existing.setWindowCloseHour(incoming.getWindowCloseHour());
            existing.setWindowTimezone(incoming.getWindowTimezone());
            existing.setRoundStart(incoming.getRoundStart());
            existing.setFifaRoundStart(incoming.getFifaRoundStart());
            // isRoundClosed is intentionally not settable via PUT — use PATCH /{stage}/close
            return ResponseEntity.ok(repo.save(existing));
        }).orElse(ResponseEntity.notFound().build());
    }
}
