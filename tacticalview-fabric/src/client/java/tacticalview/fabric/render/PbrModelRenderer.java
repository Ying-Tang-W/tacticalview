package tacticalview.fabric.render;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import javax.imageio.ImageIO;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import tacticalview.core.gltf.GltfLoader;
import tacticalview.core.gltf.GltfModel;
import tacticalview.core.mesh.StaticMesh;
import tacticalview.core.mesh.StaticMeshBaker;
import tacticalview.fabric.TacticalViewClient;

/**
 * 静态 PBR 模型渲染：自带着色器（pbr.vsh/fsh）、自己的顶点格式和 PNG 贴图上传。
 * 模型来自 /assets/tacticalview/models/kurong_pbr.glb（第三方素材，不进仓库，放进本地 resources 即可）。
 * 材质贴图：baseColor / metallicRoughness / normal / emissive；原版光照贴图做环境光。
 */
public final class PbrModelRenderer {
    private static final String MODEL_PATH = "/assets/tacticalview/models/kurong_pbr.glb";
    private static final List<String> INCLUDE = List.of("obj_model_01");

    /** pos(3f) normal(3f) uv(2f) tangent(4f) = 48 字节 */
    private static final VertexFormat FORMAT = VertexFormat.builder(0)
            .addAttribute("Position", GpuFormat.RGB32_FLOAT)
            .addAttribute("Normal", GpuFormat.RGB32_FLOAT)
            .addAttribute("UV0", GpuFormat.RG32_FLOAT)
            .addAttribute("Tangent", GpuFormat.RGBA32_FLOAT)
            .build();

    private static final BindGroupLayout TEXTURES = BindGroupLayout.builder()
            .withUniform("TvBaseColor", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("TvMetalRough", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("TvNormal", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("TvEmissive", UniformType.COMBINED_IMAGE_SAMPLER)
            .withUniform("TvLightmap", UniformType.COMBINED_IMAGE_SAMPLER)
            .build();

    private static final Identifier SHADER = Identifier.fromNamespaceAndPath(TacticalViewClient.MOD_ID, "core/pbr");

    private static final RenderPipeline PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(TacticalViewClient.MOD_ID, "pipeline/pbr_static"))
                    .withVertexShader(SHADER)
                    .withFragmentShader(SHADER)
                    .withVertexBinding(0, FORMAT)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withBindGroupLayout(BindGroupLayouts.FOG)
                    .withBindGroupLayout(BindGroupLayouts.LIGHTING)
                    .withBindGroupLayout(TEXTURES)
                    .withDepthStencilState(Optional.of(DepthStencilState.DEFAULT))
                    .withCull(false)
                    .build());

    private static final PbrModelRenderer INSTANCE = new PbrModelRenderer();

    public static PbrModelRenderer getInstance() {
        return INSTANCE;
    }

    // 资源（渲染线程里延迟创建）
    private StaticMesh mesh;
    private GltfModel gltf;
    private GpuBuffer vertexBuffer;
    private GpuBuffer indexBuffer;
    private GpuTextureView baseColor, metalRough, normal, emissive;
    private final java.util.List<GpuTexture> ownedTextures = new java.util.ArrayList<>();
    private boolean uploaded;
    private boolean failed;

    // 每帧状态
    private volatile boolean visible;
    private double anchorX, anchorY, anchorZ;
    private float yaw;
    private final Matrix4f modelMatrix = new Matrix4f();
    private final Vector4f colorModulator = new Vector4f();
    private final Vector3f materialFactors = new Vector3f();
    private final Matrix4f textureMatrix = new Matrix4f();

    private PbrModelRenderer() {}

    /** 在客户端初始化时调用：只在 CPU 端读取并烘焙，GPU 资源等第一次绘制时再建。 */
    public void load() {
        try (InputStream in = PbrModelRenderer.class.getResourceAsStream(MODEL_PATH)) {
            if (in == null) {
                TacticalViewClient.LOGGER.info("PBR model {} not present; skipping (put kurong_pbr.glb into src/client/resources/assets/tacticalview/models/)", MODEL_PATH);
                return;
            }
            this.gltf = GltfLoader.loadGlb(in.readAllBytes());
            this.mesh = StaticMeshBaker.bake(gltf, INCLUDE);
            TacticalViewClient.LOGGER.info("PBR model baked: {} vertices, {} triangles, material {}",
                    mesh.vertexCount(), mesh.triangleCount(), mesh.material);
        } catch (Exception e) {
            TacticalViewClient.LOGGER.error("Failed to load PBR model", e);
            this.mesh = null;
        }
    }

    public boolean hasModel() {
        return mesh != null && !failed;
    }

    /** 提取阶段调用：设置模型在世界中的位置与朝向（世界坐标，脚下原点）。 */
    public void place(double x, double y, double z, float yawRadians) {
        this.anchorX = x;
        this.anchorY = y;
        this.anchorZ = z;
        this.yaw = yawRadians;
        this.visible = hasModel();
    }

    public void hide() {
        this.visible = false;
    }

    // ------------------------------------------------------------------ GPU 资源

    private void ensureUploaded() {
        if (uploaded || failed) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            CommandEncoder encoder = device.createCommandEncoder();

            float[] inter = mesh.interleave();
            ByteBuffer vb = ByteBuffer.allocateDirect(inter.length * 4).order(ByteOrder.nativeOrder());
            vb.asFloatBuffer().put(inter);
            vertexBuffer = device.createBuffer(() -> "TacticalView PBR vertices",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vb);

            ByteBuffer ib = ByteBuffer.allocateDirect(mesh.indices.length * 4).order(ByteOrder.nativeOrder());
            ib.asIntBuffer().put(mesh.indices);
            indexBuffer = device.createBuffer(() -> "TacticalView PBR indices",
                    GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, ib);

            GltfModel.Material mat = (mesh.material >= 0 && mesh.material < gltf.materials.size())
                    ? gltf.materials.get(mesh.material) : new GltfModel.Material();
            materialFactors.set(mat.metallic, mat.roughness, 0f);
            baseColor = texture(device, encoder, "base", mat.baseColorImage, 255, 255, 255);
            metalRough = texture(device, encoder, "metalrough", mat.metallicRoughnessImage, 255, 255, 255);
            normal = texture(device, encoder, "normal", mat.normalImage, 128, 128, 255);
            emissive = texture(device, encoder, "emissive", mat.emissiveImage, 0, 0, 0);
            uploaded = true;
            TacticalViewClient.LOGGER.info("PBR model uploaded to the GPU");
        } catch (Throwable t) {
            failed = true;
            TacticalViewClient.LOGGER.error("Failed to upload PBR model; it will not be drawn", t);
        }
    }

    private GpuTextureView texture(GpuDevice device, CommandEncoder encoder, String label, int imageIndex, int r, int g, int b)
            throws Exception {
        byte[] png;
        if (imageIndex >= 0 && imageIndex < gltf.images.size() && gltf.images.get(imageIndex).data != null) {
            png = gltf.images.get(imageIndex).data;
        } else {
            BufferedImage solid = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            solid.setRGB(0, 0, 0xFF000000 | (r << 16) | (g << 8) | b);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(solid, "png", bos);
            png = bos.toByteArray();
        }
        try (NativeImage image = NativeImage.read(png)) {
            GpuTexture tex = device.createTexture(() -> "TacticalView " + label,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
            encoder.writeToTexture(tex, image);
            ownedTextures.add(tex);
            TacticalViewClient.LOGGER.info("PBR texture '{}': {}x{}", label, image.getWidth(), image.getHeight());
            return device.createTextureView(tex);
        }
    }

    // ------------------------------------------------------------------ 绘制

    /** 在 prepare 阶段调用：算出相机相对的模型矩阵。 */
    public void prepare(double camX, double camY, double camZ) {
        modelMatrix.identity()
                .translate((float) (anchorX - camX), (float) (anchorY - camY), (float) (anchorZ - camZ))
                .rotateY(yaw);
    }

    public void draw(RenderPass pass) {
        if (!visible) {
            return;
        }
        ensureUploaded();
        if (!uploaded) {
            return;
        }
        GpuTextureView lightmap = Minecraft.getInstance().levelLightmap();
        if (lightmap == null) {
            return;
        }
        // 光照贴图坐标：x=方块光 y=天空光（先用白天户外的固定值，后面接世界亮度）
        colorModulator.set(0.0f, 1.0f, 0f, 1f);
        Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrixCopy()).mul(modelMatrix);
        GpuBufferSlice dyn = RenderSystem.getDynamicUniforms().writeTransform(mv, colorModulator, materialFactors, textureMatrix);

        GpuSampler linear = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
        GpuSampler clamp = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        pass.pushDebugGroup(() -> "TacticalView PBR");
        pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
        RenderSystem.bindDefaultUniforms(pass);
        pass.setUniform("DynamicTransforms", dyn);
        pass.setUniform("TvBaseColor", baseColor, linear);
        pass.setUniform("TvMetalRough", metalRough, linear);
        pass.setUniform("TvNormal", normal, linear);
        pass.setUniform("TvEmissive", emissive, linear);
        pass.setUniform("TvLightmap", lightmap, clamp);
        pass.setVertexBuffer(0, vertexBuffer.slice());
        pass.setIndexBuffer(indexBuffer, IndexType.INT);
        pass.drawIndexed(mesh.indices.length, 1, 0, 0, 0);
        pass.popDebugGroup();
    }

    public void close() {
        if (vertexBuffer != null) vertexBuffer.close();
        if (indexBuffer != null) indexBuffer.close();
        for (GpuTexture t : ownedTextures) t.close();
        ownedTextures.clear();
        uploaded = false;
        vertexBuffer = null;
        indexBuffer = null;
    }
}
