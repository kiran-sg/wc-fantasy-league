package com.wc.fantasy.repository;

import com.wc.fantasy.model.UserSquad;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserSquadRepository extends JpaRepository<UserSquad, Long> {
    Optional<UserSquad> findByUserIdAndMatchId(Long userId, Long matchId);
    List<UserSquad> findByUserId(Long userId);
    List<UserSquad> findByMatchId(Long matchId);
}
