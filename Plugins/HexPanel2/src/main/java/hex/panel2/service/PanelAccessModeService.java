package hex.panel2.service;

public final class PanelAccessModeService {

    private boolean ocenyMode = false;

    public synchronized boolean isOcenyMode() {
        return ocenyMode;
    }

    public synchronized void enableOcenyMode() {
        this.ocenyMode = true;
    }

    public synchronized void disableOcenyMode() {
        this.ocenyMode = false;
    }
}
