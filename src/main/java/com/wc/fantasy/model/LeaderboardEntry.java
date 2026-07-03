package com.wc.fantasy.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LeaderboardEntry {
    private int rank;
    private AppUser user;
}
