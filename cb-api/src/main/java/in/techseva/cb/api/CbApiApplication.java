package in.techseva.cb.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"in.techseva.cb.core", "in.techseva.cb.api"})
public class CbApiApplication {
    public static void main(String[] args) {
        SpringApplication.run(CbApiApplication.class, args);
    }
}
