package hexnpc.render.packet;

import hexnpc.model.Dialogue;
import hexnpc.model.InteractionSettings;
import hexnpc.model.NpcActions;
import hexnpc.model.NpcAppearance;
import hexnpc.model.NpcDefinition;
import hexnpc.model.NpcId;
import hexnpc.model.NpcLocation;
import hexnpc.model.NpcPose;
import hexnpc.model.NpcSkin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sichert die neue Trennung von Skin-Quelle und sichtbarem Nickname sowie die
 * Glow-/Pose-Metadata-Ableitung im Renderer ab — alles ueber die
 * package-private statischen Helfer, ohne PacketEvents zu initialisieren.
 */
class PacketNpcRendererAppearanceTest {

    @Test
    void visibleNameFallsBackToIdWhenNoNickname() {
        NpcDefinition def = npc("greeter", NpcSkin.ofName("Notch"), NpcAppearance.defaults());
        assertEquals("greeter", PacketNpcRenderer.visibleName(def),
                "ohne expliziten Nick faellt der sichtbare Name auf die Id zurueck");
    }

    @Test
    void visibleNameUsesNicknameWhenSet() {
        NpcDefinition def = npc("greeter", NpcSkin.ofName("Notch"),
                new NpcAppearance("Sklepikarz", false, NpcPose.STANDING));
        assertEquals("Sklepikarz", PacketNpcRenderer.visibleName(def));
    }

    @Test
    void changingSkinDoesNotChangeVisibleName() {
        NpcAppearance appearance = new NpcAppearance("&6Król", false, NpcPose.STANDING);
        NpcDefinition before = npc("king", NpcSkin.ofName("Notch"), appearance);
        // Skin-Wechsel wie ihn /hexnpc skin ausloest: nur NpcSkin aendert sich.
        NpcDefinition after = before.withSkin(NpcSkin.ofName("Herobrine"));
        assertEquals(PacketNpcRenderer.visibleName(before), PacketNpcRenderer.visibleName(after),
                "Skin-Wechsel darf den sichtbaren Nickname nicht veraendern");
        assertEquals("&6Król", PacketNpcRenderer.visibleName(after));
    }

    @Test
    void displayComponentParsesLegacyColor() {
        NpcDefinition def = npc("sklep", NpcSkin.ofName("Notch"),
                new NpcAppearance("&6Sklepikarz", false, NpcPose.STANDING));
        Component c = PacketNpcRenderer.displayComponent(def);
        assertEquals(NamedTextColor.GOLD, c.color(), "&6 -> GOLD");
        assertEquals("Sklepikarz", ((TextComponent) c).content(), "Farbcode darf nicht im Text landen");
    }

    @Test
    void displayComponentParsesLegacyColorAndBold() {
        NpcDefinition def = npc("krol", NpcSkin.ofName("Notch"),
                new NpcAppearance("&6&lKról", false, NpcPose.STANDING));
        Component c = PacketNpcRenderer.displayComponent(def);
        assertEquals(NamedTextColor.GOLD, c.color(), "&6 -> GOLD");
        assertEquals(TextDecoration.State.TRUE, c.decoration(TextDecoration.BOLD), "&l -> bold");
        assertEquals("Król", ((TextComponent) c).content(), "polnische Zeichen bleiben erhalten");
    }

    @Test
    void profileNameIsIndependentOfNicknameAndSkinAndMaxSixteen() {
        // Weder der (farbige, > 16 Zeichen lange) Nickname noch die Skin-Quelle duerfen
        // den technischen 16-Zeichen-Profilnamen beeinflussen — er folgt nur der Id.
        NpcDefinition def = npc("shopkeeper", NpcSkin.ofName("Notch"),
                new NpcAppearance("&6&lBardzo Długi Nick Sprzedawcy", false, NpcPose.SITTING));
        String profile = PacketNpcRenderer.profileName(def);
        assertEquals("shopkeeper", profile, "Profilname folgt der Id, nicht Nickname/Skin");
        assertTrue(profile.length() <= 16);
    }

    @Test
    void nameplateCarriesColoredNicknameOnStableCustomNamePath() {
        // Kernanforderung: der Nickname landet im ECHTEN Nameplate-Pfad, nicht nur im
        // PlayerInfo. Der Nameplate-Inhalt ist der farbige Nickname und wird per Entity-
        // Metadata Custom Name (index 2, stabiles Basis-Entity-Feld) + Custom Name Visible
        // (index 3) gesendet.
        NpcDefinition def = npc("king", NpcSkin.ofName("Notch"),
                new NpcAppearance("&6&lKról", false, NpcPose.STANDING));
        Component nameplate = PacketNpcRenderer.nameplateComponent(def);

        assertEquals(PacketNpcRenderer.displayComponent(def), nameplate,
                "Nameplate-Inhalt entspricht dem sichtbaren Nickname");
        assertEquals(NamedTextColor.GOLD, nameplate.color(), "&6 -> GOLD im Nameplate");
        assertEquals(TextDecoration.State.TRUE, nameplate.decoration(TextDecoration.BOLD),
                "&l -> bold im Nameplate");
        assertEquals("Król", ((TextComponent) nameplate).content(),
                "polnische Zeichen bleiben im Nameplate erhalten");

        // Stabiler Custom-Name-Metadata-Pfad (nicht PlayerInfo).
        assertEquals(2, PacketNpcRenderer.CUSTOM_NAME_INDEX);
        assertEquals(3, PacketNpcRenderer.CUSTOM_NAME_VISIBLE_INDEX);
    }

    @Test
    void nameplateFallsBackToIdWhenNoNickname() {
        NpcDefinition def = npc("greeter", NpcSkin.ofName("Notch"), NpcAppearance.defaults());
        Component nameplate = PacketNpcRenderer.nameplateComponent(def);
        assertEquals(PacketNpcRenderer.displayComponent(def), nameplate);
        assertEquals("greeter", ((TextComponent) nameplate).content(),
                "ohne Nick zeigt die Nameplate weiterhin die Id");
    }

    @Test
    void entityFlagsEncodeGlowAndPose() {
        assertEquals(0x00, PacketNpcRenderer.entityFlags(NpcAppearance.defaults()) & 0xFF,
                "Default: keine Flags");
        assertEquals(0x40, PacketNpcRenderer.entityFlags(
                        new NpcAppearance(null, true, NpcPose.STANDING)) & 0xFF,
                "Glow -> Bit 0x40");
        assertEquals(0x02, PacketNpcRenderer.entityFlags(
                        new NpcAppearance(null, false, NpcPose.SNEAKING)) & 0xFF,
                "Sneaking -> Bit 0x02");
        assertEquals(0x10, PacketNpcRenderer.entityFlags(
                        new NpcAppearance(null, false, NpcPose.CRAWLING)) & 0xFF,
                "Crawling -> Bit 0x10 (Swimming-Flag)");
        assertEquals(0x42, PacketNpcRenderer.entityFlags(
                        new NpcAppearance(null, true, NpcPose.SNEAKING)) & 0xFF,
                "Glow + Sneaking kombinieren");
    }

    private static NpcDefinition npc(String id, NpcSkin skin, NpcAppearance appearance) {
        return new NpcDefinition(
                new NpcId(id), skin,
                new NpcLocation("world", 0, 64, 0, 0f, 0f),
                InteractionSettings.defaultClick(),
                Dialogue.empty(),
                NpcActions.empty(),
                appearance
        );
    }
}
