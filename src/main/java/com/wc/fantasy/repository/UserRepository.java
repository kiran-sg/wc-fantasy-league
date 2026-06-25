package com.wc.fantasy.repository;

import com.wc.fantasy.model.*;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    List<AppUser> findByIsAdminFalseOrIsAdminIsNullOrderByTotalPointsDesc();
}
