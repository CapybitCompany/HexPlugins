package hexnpc.workflow;

import hexnpc.workflow.action.AnvilInputAction;
import hexnpc.workflow.action.ConditionAction;
import hexnpc.workflow.action.RunWorkflowAction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowRegistryTest {
    private ServerMock server;

    @BeforeEach
    void setUp() { server = MockBukkit.mock(); }

    @AfterEach
    void tearDown() { if (MockBukkit.isMocked()) MockBukkit.unmock(); }

    @Test
    void loadsBundledCustomTagWorkflowAndMenu(@TempDir Path temp) throws Exception {
        Path file = temp.resolve("workflows.yml");
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("workflows.yml")) {
            assertNotNull(in, "missing workflows.yml test resource");
            Files.copy(in, file);
        }
        WorkflowRegistry registry = new WorkflowRegistry(file.toFile(), Logger.getLogger("workflow-test"));
        assertEquals(3, registry.reload());
        assertTrue(registry.errors().isEmpty(), registry.errors().toString());
        assertTrue(registry.hasMenu("cosmetics_tags"));

        var create = registry.workflow("custom_tag_create").orElseThrow();
        assertEquals(1, create.actions().size());
        assertInstanceOf(ConditionAction.class, create.actions().getFirst());
        ConditionAction condition = (ConditionAction) create.actions().getFirst();
        assertTrue(condition.thenActions().stream().anyMatch(AnvilInputAction.class::isInstance));

        var menu = registry.menu("cosmetics_tags").orElseThrow();
        assertEquals(27, menu.size());
        assertTrue(menu.background().hideTooltip());
        assertTrue(menu.items().get("custom_tag").actionsFor("left_click").stream()
                .anyMatch(action -> action instanceof RunWorkflowAction run
                        && run.workflow().equals("custom_tag_create")));
    }
}
