package com.wc.fantasy.repository;

import com.wc.fantasy.model.UserTeam;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserTeamRepository extends JpaRepository<UserTeam, Long> {
    Optional<UserTeam> findByUserId(Long userId);
    List<UserTeam> findAll();
}
