package in.techseva.cb.escalation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication(scanBasePackages = {"in.techseva.cb.escalation", "in.techseva.cb.core"})
@EnableAsync
public class CbEscalationApplication {

    public static void main(String[] args) {
        SpringApplication.run(CbEscalationApplication.class, args);
    }
}
