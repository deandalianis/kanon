package io.kanon.specctl.workbench.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {
    Optional<ProjectEntity> findByName(String name);

    Optional<ProjectEntity> findBySourcePath(String sourcePath);
}
