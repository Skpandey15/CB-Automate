package in.techseva.cb.notifier;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"in.techseva.cb.core", "in.techseva.cb.notifier"})
public class CbNotifierApplication {
    public static void main(String[] args) {
        SpringApplication.run(CbNotifierApplication.class, args);
    }
}
