package com.wc.fantasy.repository;

import com.wc.fantasy.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    List<AppUser> findByIsAdminFalseOrIsAdminIsNullOrderByTotalPointsDesc();

    @Query("SELECT u FROM AppUser u WHERE u.totalPoints > 0 AND (u.isAdmin = false OR u.isAdmin IS NULL) ORDER BY u.totalPoints DESC")
    List<AppUser> findLeaderboardWithPoints();
}
