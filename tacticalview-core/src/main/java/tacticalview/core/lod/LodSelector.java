package tacticalview.core.lod;

/**
 * 按距离选择 LOD 级别，带滞回避免在边界处来回闪烁。
 * 级别 0 最精细。switchDistances[i] 是级别 i 的最大使用距离；超过最后一个距离使用最粗一级。
 */
public final class LodSelector {
    private final float[] switchDistances;
    private final float hysteresis;

    /** @param switchDistances 严格递增的切换距离（方块）；@param hysteresis 滞回比例，如 0.1 表示 10%。 */
    public LodSelector(float[] switchDistances, float hysteresis) {
        for (int i = 1; i < switchDistances.length; i++) {
            if (switchDistances[i] <= switchDistances[i - 1]) {
                throw new IllegalArgumentException("LOD distances must be strictly increasing");
            }
        }
        this.switchDistances = switchDistances.clone();
        this.hysteresis = Math.max(0f, hysteresis);
    }

    public int levelCount() {
        return switchDistances.length + 1;
    }

    /** @param current 当前级别，首次传 -1。 */
    public int select(float distance, int current) {
        int raw = switchDistances.length;
        for (int i = 0; i < switchDistances.length; i++) {
            if (distance < switchDistances[i]) {
                raw = i;
                break;
            }
        }
        if (current < 0 || current >= levelCount()) {
            return raw;
        }
        if (raw > current) {
            return distance > switchDistances[current] * (1f + hysteresis) ? raw : current;
        }
        if (raw < current) {
            return distance < switchDistances[current - 1] * (1f - hysteresis) ? raw : current;
        }
        return current;
    }
}
