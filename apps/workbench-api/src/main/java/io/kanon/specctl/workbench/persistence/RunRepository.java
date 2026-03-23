package io.kanon.specctl.workbench.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RunRepository extends JpaRepository<RunEntity, String> {
    List<RunEntity> findByProjectIdOrderByStartedAtDesc(String projectId);

    Optional<RunEntity> findTopByProjectIdAndKindOrderByStartedAtDesc(String projectId, String kind);
}
