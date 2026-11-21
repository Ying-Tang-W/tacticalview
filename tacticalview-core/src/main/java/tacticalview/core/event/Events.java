package tacticalview.core.event;

import tacticalview.core.math.Mat4;

/** 核心库对外公开的事件类型。 */
public final class Events {
    private Events() {}

    /** 一个模型实例开始渲染前（可在此修改 Pose：开火后坐、瞄准偏移等）。 */
    public record PreRender(String modelId, int entityId, float partialTick) {}

    /** 动画事件（例如换弹动画里的 "mag_out" 标记）命中。 */
    public record AnimationMarker(String modelId, int entityId, String clip, String marker) {}

    /** 请求在某挂点播放特效。world 是挂点的模型空间变换，effect 为内容包特效 id。 */
    public record SocketEffect(String modelId, int entityId, String socket, String effect, Mat4 world) {}
}
