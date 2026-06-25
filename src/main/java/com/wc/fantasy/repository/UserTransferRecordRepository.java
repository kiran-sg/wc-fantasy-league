package com.wc.fantasy.repository;

import com.wc.fantasy.model.UserTransferRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserTransferRecordRepository extends JpaRepository<UserTransferRecord, Long> {
    Optional<UserTransferRecord> findByUserIdAndStage(Long userId, String stage);
    List<UserTransferRecord> findByUserId(Long userId);
}
