package hex.core.service.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class YamlConfigLoader {

    public <T> T load(Path file, Class<T> type) throws Exception {
        if (!Files.exists(file)) {
            return null;
        }

        LoaderOptions options = new LoaderOptions();
        Constructor constructor = new Constructor(type, options);
        Yaml yaml = new Yaml(constructor);

        try (InputStream in = Files.newInputStream(file)) {
            return yaml.load(in);
        }
    }

    public void saveDefault(Path file, Object defaultObj) throws Exception {
        if (Files.exists(file)) return;

        Files.createDirectories(file.getParent());
        Yaml yaml = new Yaml();
        String out = yaml.dump(defaultObj);
        Files.writeString(file, out);
    }
}
