package com.netsettle.repository;

import com.netsettle.domain.SettlementEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SettlementEntryRepository extends JpaRepository<SettlementEntry, Long> {

    List<SettlementEntry> findByRunIdOrderByBankId(Long runId);
}
