package hex.towns.heart;

public record HeartFoundationReport(
        boolean found,
        boolean activeTownProtected,
        boolean removed,
        String world,
        int centerX,
        int y,
        int centerZ,
        int matchingBlocks,
        String message
) {
    public static HeartFoundationReport notFound(String message) {
        return new HeartFoundationReport(false, false, false, null, 0, 0, 0, 0, message);
    }
}
