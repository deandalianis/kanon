package io.kanon.specctl.core.contracts;

import io.kanon.specctl.core.TestFixtures;
import io.kanon.specctl.core.platform.PlatformTypes;
import io.kanon.specctl.core.plugin.BuiltinPlugins;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ContractDiffServiceTest {
    @TempDir
    Path baselineContractsDir;

    @Test
    void diffGeneratesContractsWithDependencyAwarePluginSets() {
        ContractDiffService service = new ContractDiffService();

        PlatformTypes.ContractDiff diff = service.diff(
                TestFixtures.specFile(),
                baselineContractsDir,
                BuiltinPlugins.defaults()
        );

        assertThat(diff.addedOperations()).isNotEmpty();
        assertThat(diff.removedOperations()).isEmpty();
    }
}
