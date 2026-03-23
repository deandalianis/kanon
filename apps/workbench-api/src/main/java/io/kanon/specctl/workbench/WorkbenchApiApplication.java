package io.kanon.specctl.workbench;

import io.kanon.specctl.workbench.config.WorkbenchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(WorkbenchProperties.class)
public class WorkbenchApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(WorkbenchApiApplication.class, args);
    }
}
