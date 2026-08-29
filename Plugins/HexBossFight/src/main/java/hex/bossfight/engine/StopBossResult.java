package hex.bossfight.engine;

public record StopBossResult(boolean success, String message) {
    public static StopBossResult ok(){ return new StopBossResult(true,"OK"); }
    public static StopBossResult fail(String message){ return new StopBossResult(false,message); }
}
