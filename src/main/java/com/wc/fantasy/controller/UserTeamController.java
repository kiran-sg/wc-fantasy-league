package com.wc.fantasy.controller;

import com.wc.fantasy.model.UserTeam;
import com.wc.fantasy.model.UserTeamMatchPoints;
import com.wc.fantasy.model.UserTransferRecord;
import com.wc.fantasy.service.UserTeamService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/team")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class UserTeamController {

    private final UserTeamService teamService;

    @GetMapping
    public UserTeam getTeam(@RequestParam Long userId) {
        return teamService.getTeam(userId);
    }

    @PostMapping
    public UserTeam saveTeam(@RequestBody Map<String, Object> body) {
        Long userId      = ((Number) body.get("userId")).longValue();
        Long captainId   = ((Number) body.get("captainId")).longValue();
        Long vcId        = ((Number) body.get("viceCaptainId")).longValue();
        String stage     = (String) body.getOrDefault("stage", "R32");
        String formation = (String) body.getOrDefault("formation", "4-4-2");

        List<Long> starterIds = ((List<Number>) body.get("starterIds"))
                .stream().map(Number::longValue).toList();
        List<Long> benchIds = ((List<Number>) body.get("benchIds"))
                .stream().map(Number::longValue).toList();

        return teamService.saveTeam(userId, starterIds, benchIds, captainId, vcId, stage, formation);
    }

    @GetMapping("/points")
    public List<UserTeamMatchPoints> getPoints(@RequestParam Long userId) {
        return teamService.getMatchPoints(userId);
    }

    @GetMapping("/transfers")
    public UserTransferRecord getTransfers(@RequestParam Long userId, @RequestParam String stage) {
        return teamService.getTransferRecord(userId, stage);
    }

    @GetMapping("/transfers/all")
    public List<UserTransferRecord> getAllTransfers(@RequestParam Long userId) {
        return teamService.getAllTransferRecords(userId);
    }
}
