package in.techseva.cb.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
@ComponentScan(basePackages = {"in.techseva.cb.core", "in.techseva.cb.mcp"})
public class CBMcpServerApp {

    public static void main(String[] args) {
        SpringApplication.run(CBMcpServerApp.class, args);
    }
}
