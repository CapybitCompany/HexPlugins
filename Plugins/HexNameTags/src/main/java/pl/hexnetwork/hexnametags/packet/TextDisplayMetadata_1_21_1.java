package pl.hexnetwork.hexnametags.packet;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.util.Vector3f;
import net.kyori.adventure.text.Component;
import pl.hexnetwork.hexnametags.model.NameTagStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Metadata indexes for Display/TextDisplay used by the 1.20.5-1.21.1 protocol family.
 *
 * Entity base indexes occupy 0-7.
 * Display indexes occupy 8-22.
 * TextDisplay-specific indexes occupy 23-27.
 *
 * Important for smooth movement:
 * index 10 is Display.teleportDuration. Without it, entity teleport packets are applied
 * immediately and the TextDisplay appears to jump. With a small value (2-3 ticks), the
 * client interpolates the movement between follow packets.
 */
public final class TextDisplayMetadata_1_21_1 {
    private static final int DISPLAY_INTERPOLATION_START = 8;
    private static final int DISPLAY_INTERPOLATION_DURATION = 9;
    private static final int DISPLAY_TELEPORT_DURATION = 10;
    private static final int DISPLAY_TRANSLATION = 11;
    private static final int DISPLAY_SCALE = 12;
    private static final int DISPLAY_BILLBOARD = 15;
    private static final int DISPLAY_VIEW_RANGE = 17;
    private static final int DISPLAY_WIDTH = 20;
    private static final int DISPLAY_HEIGHT = 21;

    private static final int TEXT_TEXT = 23;
    private static final int TEXT_LINE_WIDTH = 24;
    private static final int TEXT_BACKGROUND_COLOR = 25;
    private static final int TEXT_OPACITY = 26;
    private static final int TEXT_FLAGS = 27;

    public List<EntityData<?>> create(Component text, NameTagStyle style, int lineCount, int teleportDurationTicks) {
        int safeTeleportDuration = Math.max(0, Math.min(59, teleportDurationTicks));
        List<EntityData<?>> metadata = new ArrayList<>();

        // Transformation interpolation is not required for normal movement, but setting stable values
        // avoids client-side surprises when text/style metadata is updated.
        metadata.add(new EntityData<>(DISPLAY_INTERPOLATION_START, EntityDataTypes.INT, 0));
        metadata.add(new EntityData<>(DISPLAY_INTERPOLATION_DURATION, EntityDataTypes.INT, 0));
        metadata.add(new EntityData<>(DISPLAY_TELEPORT_DURATION, EntityDataTypes.INT, safeTeleportDuration));

        metadata.add(new EntityData<>(DISPLAY_TRANSLATION, EntityDataTypes.VECTOR3F,
                new Vector3f(style.translationX(), style.displayTranslationY(lineCount), style.translationZ())));
        metadata.add(new EntityData<>(DISPLAY_SCALE, EntityDataTypes.VECTOR3F,
                new Vector3f(style.scale(), style.scale(), style.scale())));
        metadata.add(new EntityData<>(DISPLAY_BILLBOARD, EntityDataTypes.BYTE, style.billboard().protocolId()));
        metadata.add(new EntityData<>(DISPLAY_VIEW_RANGE, EntityDataTypes.FLOAT, style.displayViewRange()));
        metadata.add(new EntityData<>(DISPLAY_WIDTH, EntityDataTypes.FLOAT, 0.0F));
        metadata.add(new EntityData<>(DISPLAY_HEIGHT, EntityDataTypes.FLOAT, 0.0F));

        metadata.add(new EntityData<>(TEXT_TEXT, EntityDataTypes.ADV_COMPONENT, text));
        metadata.add(new EntityData<>(TEXT_LINE_WIDTH, EntityDataTypes.INT, style.lineWidth()));
        metadata.add(new EntityData<>(TEXT_BACKGROUND_COLOR, EntityDataTypes.INT, style.backgroundColor()));
        metadata.add(new EntityData<>(TEXT_OPACITY, EntityDataTypes.BYTE, style.textOpacity()));
        metadata.add(new EntityData<>(TEXT_FLAGS, EntityDataTypes.BYTE, style.textFlags()));

        return metadata;
    }
}
