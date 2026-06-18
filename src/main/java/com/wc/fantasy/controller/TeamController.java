package com.wc.fantasy.controller;

import com.wc.fantasy.model.Team;
import com.wc.fantasy.repository.TeamRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class TeamController {

    private final TeamRepository teamRepo;

    @GetMapping
    public List<Team> getAll() {
        return teamRepo.findAll();
    }
}
