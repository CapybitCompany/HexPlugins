package hex.core.service.ui;

/**
 * YAML-friendly preset definition for ui.yml.
 */
public final class UiPresetConfig {

    private String chat;
    private boolean chatBroadcast;

    private String actionbar;
    private boolean actionbarBroadcast;

    private String title;
    private String subtitle;
    private boolean titleBroadcast;

    private String sound;
    private boolean soundBroadcast;
    private float volume = 1.0f;
    private float pitch = 1.0f;

    public String getChat() { return chat; }
    public void setChat(String chat) { this.chat = chat; }

    public boolean isChatBroadcast() { return chatBroadcast; }
    public void setChatBroadcast(boolean chatBroadcast) { this.chatBroadcast = chatBroadcast; }

    public String getActionbar() { return actionbar; }
    public void setActionbar(String actionbar) { this.actionbar = actionbar; }

    public boolean isActionbarBroadcast() { return actionbarBroadcast; }
    public void setActionbarBroadcast(boolean actionbarBroadcast) { this.actionbarBroadcast = actionbarBroadcast; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getSubtitle() { return subtitle; }
    public void setSubtitle(String subtitle) { this.subtitle = subtitle; }

    public boolean isTitleBroadcast() { return titleBroadcast; }
    public void setTitleBroadcast(boolean titleBroadcast) { this.titleBroadcast = titleBroadcast; }

    public String getSound() { return sound; }
    public void setSound(String sound) { this.sound = sound; }

    public boolean isSoundBroadcast() { return soundBroadcast; }
    public void setSoundBroadcast(boolean soundBroadcast) { this.soundBroadcast = soundBroadcast; }

    public float getVolume() { return volume; }
    public void setVolume(float volume) { this.volume = volume; }

    public float getPitch() { return pitch; }
    public void setPitch(float pitch) { this.pitch = pitch; }
}

