package com.wc.fantasy.repository;

import com.wc.fantasy.model.UserTeamMatchPoints;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserTeamMatchPointsRepository extends JpaRepository<UserTeamMatchPoints, Long> {
    List<UserTeamMatchPoints> findByUserTeamId(Long userTeamId);
    Optional<UserTeamMatchPoints> findByUserTeamIdAndMatchId(Long userTeamId, Long matchId);
    List<UserTeamMatchPoints> findByMatchId(Long matchId);
}
