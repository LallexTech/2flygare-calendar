package se.flygare;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class FlygareApplication {
    public static void main(String[] args) {
        SpringApplication.run(FlygareApplication.class, args);
    }
}
