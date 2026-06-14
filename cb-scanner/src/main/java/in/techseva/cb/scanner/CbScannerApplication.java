package in.techseva.cb.scanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@ComponentScan(basePackages = {"in.techseva.cb.core", "in.techseva.cb.scanner"})
public class CbScannerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CbScannerApplication.class, args);
    }
}
