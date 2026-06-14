package in.techseva.cb.patcher;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"in.techseva.cb.core", "in.techseva.cb.patcher"})
public class CbPatcherApplication {
    public static void main(String[] args) {
        SpringApplication.run(CbPatcherApplication.class, args);
    }
}
