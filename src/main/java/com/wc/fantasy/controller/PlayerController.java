package com.wc.fantasy.controller;

import com.wc.fantasy.model.Player;
import com.wc.fantasy.repository.PlayerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/players")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PlayerController {

    private final PlayerRepository playerRepo;

    @GetMapping
    public List<Player> getAll() {
        return playerRepo.findAll();
    }

    @GetMapping("/team/{teamId}")
    public List<Player> getByTeam(@PathVariable Long teamId) {
        return playerRepo.findByTeamId(teamId);
    }
}
