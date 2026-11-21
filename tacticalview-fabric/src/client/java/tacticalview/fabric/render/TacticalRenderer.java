package tacticalview.fabric.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelTerrainRenderContext;

import tacticalview.fabric.TacticalViewClient;

/**
 * 自定义世界渲染：提取阶段生成不可变的 {@link GeometryRenderState}，
 * 绘制阶段把它写进 StagedVertexBuffer 并通过 RenderPass 提交。
 *
 * 结构参照 Fabric 官方文档 "Rendering in the World"（fabric-docs，CustomRenderPipeline）。
 * 这一版只画一个测试立方体，用来确认渲染管线已接通；之后把状态来源换成 GLB 网格即可。
 */
public final class TacticalRenderer {
    private static final TacticalRenderer INSTANCE = new TacticalRenderer();

    /**
     * 原版的带深度测试的纯色填充管线（官方文档说明它不会穿墙）。
     * 第一版用自建的 DEBUG_FILLED_SNIPPET 管线实测没有深度测试，远处的面会透过近处的面显示，
     * 所以改用原版这条。后面做 PBR 时再注册我们自己的着色器管线。
     */
    private static final RenderPipeline PIPELINE = RenderPipelines.DEBUG_FILLED_BOX;

    private static final StagedVertexBuffer STAGED_BUFFER =
            new StagedVertexBuffer(() -> "TacticalView Geometry Buffer", RenderType.SMALL_BUFFER_SIZE);

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    /** 外部（如 GLB 加载器）提供的状态，优先于测试立方体。 */
    private volatile GeometryRenderState externalState;
    /** 提取阶段生成的测试立方体；绘制阶段只读它的快照。 */
    private volatile GeometryRenderState testState;
    /** 测试立方体锚点所属的玩家对象：换世界/重生会产生新的 LocalPlayer，借此重新定位。 */
    private LocalPlayer anchoredFor;

    private StagedVertexBuffer.Draw pendingDraw;
    private boolean warnedBadTopology;

    private TacticalRenderer() {}

    public static TacticalRenderer getInstance() {
        return INSTANCE;
    }

    public static void init() {
        LevelExtractionEvents.END_EXTRACTION.register(INSTANCE::extract);
        LevelRenderEvents.BEFORE_RENDER_PASS.register(INSTANCE::prepare);
        LevelRenderEvents.END_MAIN.register(_ -> STAGED_BUFFER.endFrame());
        TacticalViewClient.LOGGER.info("TacticalView render hooks registered");
    }

    /** 之后外部（如 GLB 加载器）可以直接提供自己的状态；传 null 恢复为测试立方体。 */
    public void setState(GeometryRenderState state) {
        this.externalState = state;
    }

    // ------------------------------------------------------------------ 提取阶段

    private void extract(LevelExtractionContext context) {
        // 只有这里可以读世界/实体数据；绘制阶段只能用不可变的渲染状态。
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            this.anchoredFor = null;
            this.testState = null;
            return;
        }
        if (this.anchoredFor != player || this.testState == null) {
            Vec3 feet = player.position();
            Vec3 look = player.getLookAngle();
            double lx = look.x, lz = look.z;
            double len = Math.sqrt(lx * lx + lz * lz);
            if (len < 1e-4) {
                lx = 0;
                lz = 1;
                len = 1;
            }
            // 玩家正前方 3 格（只取水平方向），固定在世界里不再随视角移动
            this.testState = GeometryRenderState.coloredCube(
                    feet.x + lx / len * 3.0, feet.y, feet.z + lz / len * 3.0);
            this.anchoredFor = player;
        }
    }

    // ------------------------------------------------------------------ 绘制阶段

    private void prepare(LevelTerrainRenderContext context) {
        this.pendingDraw = null;
        GeometryRenderState s = this.externalState != null ? this.externalState : this.testState;
        if (s == null || s.triangleCount() == 0) {
            return;
        }
        PrimitiveTopology topology = PIPELINE.getPrimitiveTopology();
        if (topology != PrimitiveTopology.QUADS && topology != PrimitiveTopology.TRIANGLES) {
            if (!this.warnedBadTopology) {
                this.warnedBadTopology = true;
                TacticalViewClient.LOGGER.error(
                        "Pipeline primitive topology {} is not supported (need QUADS or TRIANGLES); nothing will be drawn", topology);
            }
            return;
        }
        VertexFormat format = PIPELINE.getVertexFormatBinding(0);
        assert format != null;

        StagedVertexBuffer.Draw draw = STAGED_BUFFER.appendDraw(format, topology,
                topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

        Vec3 camera = context.levelState().cameraRenderState.pos;
        Matrix4f matrix = new Matrix4f().translate(
                (float) (s.anchorX() - camera.x),
                (float) (s.anchorY() - camera.y),
                (float) (s.anchorZ() - camera.z));

        VertexConsumer builder = STAGED_BUFFER.getVertexBuilder(draw);
        writeVertices(matrix, builder, s, topology == PrimitiveTopology.QUADS);

        STAGED_BUFFER.upload();
        this.pendingDraw = draw;
    }

    private static void writeVertices(Matrix4f matrix, VertexConsumer builder, GeometryRenderState s, boolean asQuads) {
        float[] pos = s.positions();
        int[] col = s.colors();
        int[] idx = s.indices();
        for (int t = 0; t < idx.length; t += 3) {
            vertex(matrix, builder, pos, col, idx[t]);
            vertex(matrix, builder, pos, col, idx[t + 1]);
            vertex(matrix, builder, pos, col, idx[t + 2]);
            if (asQuads) {
                // 四边形拓扑下，把三角形补成"退化四边形"（最后一个顶点重复一次）
                vertex(matrix, builder, pos, col, idx[t + 2]);
            }
        }
    }

    private static void vertex(Matrix4f matrix, VertexConsumer builder, float[] pos, int[] col, int i) {
        int c = col[i];
        float a = ((c >>> 24) & 0xFF) / 255f;
        float r = ((c >>> 16) & 0xFF) / 255f;
        float g = ((c >>> 8) & 0xFF) / 255f;
        float b = (c & 0xFF) / 255f;
        builder.addVertex(matrix, pos[i * 3], pos[i * 3 + 1], pos[i * 3 + 2]).setColor(r, g, b, a);
    }

    /** 由 LevelRendererMixin 在半透明地形绘制之后调用，renderPass 是主场景的渲染通道。 */
    public void draw(RenderPass renderPass) {
        StagedVertexBuffer.Draw draw = this.pendingDraw;
        if (draw == null) {
            return;
        }
        StagedVertexBuffer.ExecuteInfo info = STAGED_BUFFER.getExecuteInfo(draw);
        if (info == null) {
            return;
        }
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        renderPass.pushDebugGroup(() -> "TacticalView Geometry");
        renderPass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
        RenderSystem.bindDefaultUniforms(renderPass);
        renderPass.setUniform("DynamicTransforms", dynamicTransforms);
        renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
        renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());
        renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        renderPass.popDebugGroup();
    }

    /** 由 GameRendererMixin 在渲染器关闭时调用。 */
    public static void close() {
        STAGED_BUFFER.close();
    }
}
