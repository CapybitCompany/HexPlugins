package pl.hexnetwork.hexnametags.api;

public final class HexNameTagsProvider {
    private static HexNameTagsApi api;

    private HexNameTagsProvider() {
    }

    public static HexNameTagsApi get() {
        if (api == null) {
            throw new IllegalStateException("HexNameTags API is not loaded yet");
        }
        return api;
    }

    public static void register(HexNameTagsApi api) {
        HexNameTagsProvider.api = api;
    }

    public static void unregister() {
        HexNameTagsProvider.api = null;
    }
}
