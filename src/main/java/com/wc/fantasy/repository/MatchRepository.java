package com.wc.fantasy.repository;

import com.wc.fantasy.model.Match;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MatchRepository extends JpaRepository<Match, Long> {
    List<Match> findByStatusOrderByMatchTimeAsc(String status);
    List<Match> findAllByOrderByMatchTimeAsc();
}
