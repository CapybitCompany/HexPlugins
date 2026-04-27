package hex.statsapi.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@Validated
@ConfigurationProperties(prefix = "stats")
public class StatsProperties {

    @Valid
    @NotEmpty
    private List<StatDefinition> definitions = new ArrayList<>();

    public List<StatDefinition> getDefinitions() {
        return definitions;
    }

    public void setDefinitions(List<StatDefinition> definitions) {
        this.definitions = definitions;
    }
}

