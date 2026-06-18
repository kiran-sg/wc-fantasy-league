package com.wc.fantasy.repository;

import com.wc.fantasy.model.Team;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {
}
