package io.kanon.specctl.workbench.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RunRepository extends JpaRepository<RunEntity, String> {
    List<RunEntity> findByProjectIdOrderByStartedAtDesc(String projectId);

    Optional<RunEntity> findTopByProjectIdAndKindOrderByStartedAtDesc(String projectId, String kind);
}
