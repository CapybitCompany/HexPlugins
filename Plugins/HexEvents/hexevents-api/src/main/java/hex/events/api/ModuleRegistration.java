package hex.events.api;

public interface ModuleRegistration extends AutoCloseable {
    String moduleId();
    boolean active();
    @Override void close();
}
