package pl.hexnetwork.hexnametags.model;

public final class RenderedTag {
    private final int entityId;
    private final boolean mounted;
    private String signature;
    private boolean hasLastPosition;
    private double lastX;
    private double lastY;
    private double lastZ;

    public RenderedTag(int entityId, String signature, boolean mounted) {
        this.entityId = entityId;
        this.signature = signature;
        this.mounted = mounted;
    }

    public int entityId() {
        return entityId;
    }

    public boolean mounted() {
        return mounted;
    }

    public String signature() {
        return signature;
    }

    public void signature(String signature) {
        this.signature = signature;
    }

    public boolean hasLastPosition() {
        return hasLastPosition;
    }

    public double lastX() {
        return lastX;
    }

    public double lastY() {
        return lastY;
    }

    public double lastZ() {
        return lastZ;
    }

    public void lastPosition(double x, double y, double z) {
        this.hasLastPosition = true;
        this.lastX = x;
        this.lastY = y;
        this.lastZ = z;
    }
}
