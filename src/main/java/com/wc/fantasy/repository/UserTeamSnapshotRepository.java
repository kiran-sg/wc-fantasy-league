package com.wc.fantasy.repository;

import com.wc.fantasy.model.UserTeamSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserTeamSnapshotRepository extends JpaRepository<UserTeamSnapshot, Long> {
    Optional<UserTeamSnapshot> findByUserIdAndStage(Long userId, String stage);
    List<UserTeamSnapshot> findByUserIdOrderByStageAsc(Long userId);
}
