package hex.core.api.ui;

/**
 * Reusable UI action bundle (chat/actionbar/title/sound).
 */
public final class UiPreset {

    private final String chatTemplateKey;
    private final boolean chatBroadcast;
    private final String actionbarTemplateKey;
    private final boolean actionbarBroadcast;
    private final String titleTemplateKey;
    private final String subtitleTemplateKey;
    private final boolean titleBroadcast;
    private final String sound;
    private final boolean soundBroadcast;
    private final float soundVolume;
    private final float soundPitch;

    private UiPreset(Builder b) {
        this.chatTemplateKey = b.chatTemplateKey;
        this.chatBroadcast = b.chatBroadcast;
        this.actionbarTemplateKey = b.actionbarTemplateKey;
        this.actionbarBroadcast = b.actionbarBroadcast;
        this.titleTemplateKey = b.titleTemplateKey;
        this.subtitleTemplateKey = b.subtitleTemplateKey;
        this.titleBroadcast = b.titleBroadcast;
        this.sound = b.sound;
        this.soundBroadcast = b.soundBroadcast;
        this.soundVolume = b.soundVolume;
        this.soundPitch = b.soundPitch;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String chatTemplateKey() { return chatTemplateKey; }
    public boolean chatBroadcast() { return chatBroadcast; }
    public String actionbarTemplateKey() { return actionbarTemplateKey; }
    public boolean actionbarBroadcast() { return actionbarBroadcast; }
    public String titleTemplateKey() { return titleTemplateKey; }
    public String subtitleTemplateKey() { return subtitleTemplateKey; }
    public boolean titleBroadcast() { return titleBroadcast; }
    public String sound() { return sound; }
    public boolean soundBroadcast() { return soundBroadcast; }
    public float soundVolume() { return soundVolume; }
    public float soundPitch() { return soundPitch; }

    public static final class Builder {
        private String chatTemplateKey;
        private boolean chatBroadcast;
        private String actionbarTemplateKey;
        private boolean actionbarBroadcast;
        private String titleTemplateKey;
        private String subtitleTemplateKey;
        private boolean titleBroadcast;
        private String sound;
        private boolean soundBroadcast;
        private float soundVolume = 1.0f;
        private float soundPitch = 1.0f;

        private Builder() {
        }

        public Builder chat(String templateKey) {
            this.chatTemplateKey = templateKey;
            this.chatBroadcast = false;
            return this;
        }

        public Builder broadcastChat(String templateKey) {
            this.chatTemplateKey = templateKey;
            this.chatBroadcast = true;
            return this;
        }

        public Builder actionbar(String templateKey) {
            this.actionbarTemplateKey = templateKey;
            this.actionbarBroadcast = false;
            return this;
        }

        public Builder broadcastActionbar(String templateKey) {
            this.actionbarTemplateKey = templateKey;
            this.actionbarBroadcast = true;
            return this;
        }

        public Builder title(String titleKey, String subtitleKey) {
            this.titleTemplateKey = titleKey;
            this.subtitleTemplateKey = subtitleKey;
            this.titleBroadcast = false;
            return this;
        }

        public Builder broadcastTitle(String titleKey, String subtitleKey) {
            this.titleTemplateKey = titleKey;
            this.subtitleTemplateKey = subtitleKey;
            this.titleBroadcast = true;
            return this;
        }

        public Builder sound(String soundName) {
            this.sound = soundName;
            this.soundBroadcast = false;
            return this;
        }

        public Builder broadcastSound(String soundName) {
            this.sound = soundName;
            this.soundBroadcast = true;
            return this;
        }

        public Builder soundVolume(float soundVolume) {
            this.soundVolume = soundVolume;
            return this;
        }

        public Builder soundPitch(float soundPitch) {
            this.soundPitch = soundPitch;
            return this;
        }

        public UiPreset build() {
            return new UiPreset(this);
        }
    }
}

