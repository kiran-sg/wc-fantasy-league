package com.wc.fantasy.controller;

import com.wc.fantasy.model.UserSquad;
import com.wc.fantasy.service.SquadService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/squads")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class SquadController {

    private final SquadService squadService;

    @PostMapping
    public UserSquad saveSquad(@RequestBody Map<String, Object> body) {
        Long userId      = ((Number) body.get("userId")).longValue();
        Long matchId     = ((Number) body.get("matchId")).longValue();
        Long captainId   = ((Number) body.get("captainId")).longValue();
        Long vcId        = ((Number) body.get("viceCaptainId")).longValue();

        List<Long> startingIds = ((List<Number>) body.get("playerIds"))
                .stream().map(Number::longValue).toList();

        List<Long> benchIds = body.containsKey("benchIds")
                ? ((List<Number>) body.get("benchIds")).stream().map(Number::longValue).toList()
                : List.of();

        return squadService.saveSquad(userId, matchId, startingIds, captainId, vcId, benchIds);
    }

    @PostMapping("/{squadId}/captain")
    public UserSquad changeCaptain(@PathVariable Long squadId, @RequestBody Map<String, Object> body) {
        Long newCaptainId = ((Number) body.get("captainId")).longValue();
        return squadService.changeCaptain(squadId, newCaptainId);
    }

    @PostMapping("/{squadId}/sub")
    public UserSquad manualSub(@PathVariable Long squadId, @RequestBody Map<String, Object> body) {
        Long outPlayerId = ((Number) body.get("outPlayerId")).longValue();
        Long inPlayerId  = ((Number) body.get("inPlayerId")).longValue();
        return squadService.manualSub(squadId, outPlayerId, inPlayerId);
    }

    @GetMapping("/{userId}/{matchId}")
    public UserSquad getSquad(@PathVariable Long userId, @PathVariable Long matchId) {
        return squadService.getSquad(userId, matchId);
    }

    @GetMapping("/{userId}")
    public List<UserSquad> getUserSquads(@PathVariable Long userId) {
        return squadService.getUserSquads(userId);
    }

    @PostMapping("/calculate/{matchId}")
    public void calculatePoints(@PathVariable Long matchId) {
        squadService.calculatePoints(matchId);
    }
}
