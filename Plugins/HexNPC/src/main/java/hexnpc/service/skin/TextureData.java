package hexnpc.service.skin;

/**
 * Signierte Minecraft-Textures-Property: {@code value} (Base64-JSON) und die
 * dazugehoerige {@code signature}. Beides ist noetig, damit der Client den Skin
 * einer fremden GameProfile-Property vertraut und rendert.
 */
public record TextureData(String value, String signature) {
    public boolean isComplete() {
        return value != null && !value.isBlank() && signature != null && !signature.isBlank();
    }
}
