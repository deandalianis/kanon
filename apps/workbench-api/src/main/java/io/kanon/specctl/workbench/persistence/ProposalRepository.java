package io.kanon.specctl.workbench.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProposalRepository extends JpaRepository<ProposalEntity, String> {
    List<ProposalEntity> findByProjectIdOrderByCreatedAtDesc(String projectId);
}
