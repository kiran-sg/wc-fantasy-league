package com.wc.fantasy.model;

import lombok.Data;

@Data
public class LeaderboardEntry {
    private int rank;
    private int finalPoints;
    private Long userId;
    private String username;
    private String displayName;
    private String location;

    public LeaderboardEntry(int rank, AppUser user) {
        this.rank = rank;
        this.finalPoints = (user.getTotalPoints() != null ? user.getTotalPoints() : 0)
                         + (user.getMissedPoints() != null ? user.getMissedPoints() : 0);
        this.userId = user.getId();
        this.username = user.getUsername();
        this.displayName = user.getDisplayName();
        this.location = user.getLocation();
    }
}
