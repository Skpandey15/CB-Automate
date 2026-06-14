package in.techseva.cb.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"in.techseva.cb.core", "in.techseva.cb.agent"})
public class CbAgentApplication {
    public static void main(String[] args) {
        SpringApplication.run(CbAgentApplication.class, args);
    }
}
