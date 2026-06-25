package com.wc.fantasy.model;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "user_transfer_records",
        uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "stage"}))
public class UserTransferRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @Column(nullable = false)
    private String stage;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private Integer transfersMade = 0;

    @Column(nullable = false, columnDefinition = "integer default 0")
    private Integer penaltyPoints = 0;
}
