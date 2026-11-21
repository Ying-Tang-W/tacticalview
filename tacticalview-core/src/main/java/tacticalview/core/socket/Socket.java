package tacticalview.core.socket;

/** 挂点定义：挂在某个骨骼上的局部偏移，用于绑定枪口火焰、弹壳、火花等特效或附件。 */
public final class Socket {
    public final String name;
    /** 目标骨骼/节点名（按规范化名字匹配，"mixamorig:RightHand" 与 "RightHand" 等价）。 */
    public final String bone;
    public final float[] position;
    /** 四元数 (x, y, z, w)。 */
    public final float[] rotation;
    public final float[] scale;
    /** 内容包里的特效配置 id，可为 null；核心库不解释它，只原样交给事件监听者。 */
    public final String effect;
    /** bind() 之后有效，未解析为 -1。 */
    int nodeIndex = -1;

    public Socket(String name, String bone, float[] position, float[] rotation, float[] scale, String effect) {
        this.name = name;
        this.bone = bone;
        this.position = position;
        this.rotation = rotation;
        this.scale = scale;
        this.effect = effect;
    }

    public int nodeIndex() {
        return nodeIndex;
    }
}
