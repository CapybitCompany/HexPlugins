package hex.statsapi;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class StatsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(StatsApiApplication.class, args);
    }
}

