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
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
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
    /** 模型候选，按顺序取第一个存在的：路径、只取名字含这些子串的节点、缩放、离地高度、是否自转。 */
    private record Spec(String path, List<String> include, float scale, float yOffset, boolean spin) {}

    private static final List<Spec> SPECS = List.of(
            new Spec("/assets/tacticalview/models/wangzhaojun.glb", null, 0.9f, 0f, false),
            new Spec("/assets/tacticalview/models/kurong_pbr.glb", List.of("obj_model_01"), 1f, 1.8f, true));

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

    /** 半透明版本：标准 alpha 混合，深度只测试不写入（先画不透明部分，再画它）。 */
    private static final RenderPipeline PIPELINE_BLEND = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(TacticalViewClient.MOD_ID, "pipeline/pbr_static_blend"))
                    .withVertexShader(SHADER)
                    .withFragmentShader(SHADER)
                    .withVertexBinding(0, FORMAT)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .withBindGroupLayout(BindGroupLayouts.FOG)
                    .withBindGroupLayout(BindGroupLayouts.LIGHTING)
                    .withBindGroupLayout(TEXTURES)
                    .withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
                    .withDepthStencilState(Optional.of(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, false)))
                    .withCull(false)
                    .build());

    private static final PbrModelRenderer INSTANCE = new PbrModelRenderer();

    public static PbrModelRenderer getInstance() {
        return INSTANCE;
    }

    /** 一个材质对应一个部件。 */
    private static final class Part {
        final StaticMesh mesh;
        final Vector3f factors = new Vector3f();
        boolean blend;
        GpuBuffer vertexBuffer, indexBuffer;
        GpuTextureView baseColor, metalRough, normal, emissive;

        Part(StaticMesh mesh) {
            this.mesh = mesh;
        }
    }

    // 资源（渲染线程里延迟创建）
    private GltfModel gltf;
    private Spec spec;
    private final List<Part> parts = new java.util.ArrayList<>();
    private final java.util.Map<Integer, GpuTextureView> viewByImage = new java.util.HashMap<>();
    private final java.util.Map<String, GpuTextureView> solidViews = new java.util.HashMap<>();
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

    /** 在客户端初始化时调用：只在 CPU 端读取并烘焙，GPU 资源等第一次绘制前再建。 */
    public void load() {
        for (Spec sp : SPECS) {
            try (InputStream in = PbrModelRenderer.class.getResourceAsStream(sp.path())) {
                if (in == null) {
                    continue;
                }
                GltfModel g = GltfLoader.loadGlb(in.readAllBytes());
                List<StaticMesh> baked = StaticMeshBaker.bakeByMaterial(g, sp.include());
                this.gltf = g;
                this.spec = sp;
                int tris = 0;
                java.util.Map<Integer, Boolean> partialCache = new java.util.HashMap<>();
                for (StaticMesh m : baked) {
                    Part part = new Part(m);
                    if (m.material >= 0 && m.material < g.materials.size()) {
                        GltfModel.Material mat = g.materials.get(m.material);
                        if ("BLEND".equals(mat.alphaMode) && mat.baseColorImage >= 0) {
                            part.blend = partialCache.computeIfAbsent(mat.baseColorImage, i -> hasPartialAlpha(g, i));
                        }
                    }
                    parts.add(part);
                    tris += m.triangleCount();
                }
                // 不透明部分在前，半透明在后
                parts.sort(java.util.Comparator.comparing((Part pt) -> pt.blend));
                TacticalViewClient.LOGGER.info("PBR model {} baked: {} parts, {} triangles", sp.path(), parts.size(), tris);
                return;
            } catch (Exception e) {
                TacticalViewClient.LOGGER.error("Failed to load PBR model " + sp.path(), e);
                parts.clear();
            }
        }
        TacticalViewClient.LOGGER.info("No PBR model found (put wangzhaojun.glb or kurong_pbr.glb into src/client/resources/assets/tacticalview/models/)");
    }

    /** BLEND 材质的贴图里是否真有"半透明"像素（>2% 的像素 alpha 在 10..245 之间）；否则当作镂空处理。 */
    private static boolean hasPartialAlpha(GltfModel g, int imageIndex) {
        try {
            byte[] data = g.images.get(imageIndex).data;
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (img == null || !img.getColorModel().hasAlpha()) {
                return false;
            }
            long partial = 0, total = (long) img.getWidth() * img.getHeight();
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    int a = img.getRGB(x, y) >>> 24;
                    if (a >= 10 && a <= 245) partial++;
                }
            }
            return partial > total / 50;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean hasModel() {
        return !parts.isEmpty() && !failed;
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
            for (Part part : parts) {
                float[] inter = part.mesh.interleave();
                ByteBuffer vb = ByteBuffer.allocateDirect(inter.length * 4).order(ByteOrder.nativeOrder());
                vb.asFloatBuffer().put(inter);
                part.vertexBuffer = device.createBuffer(() -> "TacticalView PBR vertices",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vb);
                ByteBuffer ib = ByteBuffer.allocateDirect(part.mesh.indices.length * 4).order(ByteOrder.nativeOrder());
                ib.asIntBuffer().put(part.mesh.indices);
                part.indexBuffer = device.createBuffer(() -> "TacticalView PBR indices",
                        GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, ib);

                int mi = part.mesh.material;
                GltfModel.Material mat = (mi >= 0 && mi < gltf.materials.size()) ? gltf.materials.get(mi) : new GltfModel.Material();
                boolean hasMr = mat.metallicRoughnessImage >= 0;
                // 没有金属度/粗糙度贴图时，glTF 默认的 metallic=1 会让整件东西变成黑色金属，改用非金属的保守值
                part.factors.set(hasMr ? mat.metallic : 0f, hasMr ? mat.roughness : 0.7f, part.blend ? 1f : 0f);
                part.baseColor = texture(device, encoder, mat.baseColorImage, 255, 255, 255);
                part.metalRough = texture(device, encoder, mat.metallicRoughnessImage, 255, 255, 255);
                part.normal = texture(device, encoder, mat.normalImage, 128, 128, 255);
                part.emissive = texture(device, encoder, mat.emissiveImage, 0, 0, 0);
            }
            uploaded = true;
            TacticalViewClient.LOGGER.info("PBR model uploaded to the GPU: {} parts, {} textures", parts.size(), ownedTextures.size());
        } catch (Throwable t) {
            failed = true;
            TacticalViewClient.LOGGER.error("Failed to upload PBR model; it will not be drawn", t);
        }
    }

    /** 同一张图片只上传一次；没有图片时用 1x1 纯色贴图（同色共用）。 */
    private GpuTextureView texture(GpuDevice device, CommandEncoder encoder, int imageIndex, int r, int g, int b)
            throws Exception {
        boolean real = imageIndex >= 0 && imageIndex < gltf.images.size() && gltf.images.get(imageIndex).data != null;
        if (real) {
            GpuTextureView cached = viewByImage.get(imageIndex);
            if (cached != null) {
                return cached;
            }
        } else {
            GpuTextureView cached = solidViews.get(r + "," + g + "," + b);
            if (cached != null) {
                return cached;
            }
        }
        byte[] png;
        if (real) {
            png = gltf.images.get(imageIndex).data;
        } else {
            BufferedImage solid = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
            solid.setRGB(0, 0, 0xFF000000 | (r << 16) | (g << 8) | b);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(solid, "png", bos);
            png = bos.toByteArray();
        }
        try (NativeImage image = NativeImage.read(png)) {
            GpuTexture tex = device.createTexture(() -> "TacticalView image " + imageIndex,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST,
                    GpuFormat.RGBA8_UNORM, image.getWidth(), image.getHeight(), 1, 1);
            encoder.writeToTexture(tex, image);
            ownedTextures.add(tex);
            GpuTextureView view = device.createTextureView(tex);
            if (real) {
                viewByImage.put(imageIndex, view);
            } else {
                solidViews.put(r + "," + g + "," + b, view);
            }
            return view;
        }
    }

    // ------------------------------------------------------------------ 绘制

    /** 在 prepare 阶段调用：算出相机相对的模型矩阵。 */
    public void prepare(double camX, double camY, double camZ) {
        // GPU 上传必须在任何渲染通道之外进行，所以放在这里而不是 draw()
        if (visible) {
            ensureUploaded();
        }
        modelMatrix.identity()
                .translate((float) (anchorX - camX), (float) (anchorY - camY), (float) (anchorZ - camZ))
                .rotateY(yaw)
                .scale(spec != null ? spec.scale() : 1f);
    }

    public void draw(RenderPass pass) {
        if (!visible || !uploaded) {
            return;
        }
        GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.levelLightmap();
        if (lightmap == null) {
            return;
        }
        // 光照贴图坐标：x=方块光 y=天空光（先用白天户外的固定值，后面接世界亮度）
        colorModulator.set(0.0f, 1.0f, 0f, 1f);
        Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrixCopy()).mul(modelMatrix);
        GpuSampler linear = RenderSystem.getSamplerCache().getRepeat(FilterMode.LINEAR);
        GpuSampler clamp = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);

        pass.pushDebugGroup(() -> "TacticalView PBR");
        boolean currentBlend = false;
        pass.setPipeline(RenderSystem.getCompiledPipeline(PIPELINE));
        RenderSystem.bindDefaultUniforms(pass);
        for (Part part : parts) {
            if (part.blend != currentBlend) {
                currentBlend = part.blend;
                pass.setPipeline(RenderSystem.getCompiledPipeline(currentBlend ? PIPELINE_BLEND : PIPELINE));
                RenderSystem.bindDefaultUniforms(pass);
            }
            GpuBufferSlice dyn = RenderSystem.getDynamicUniforms().writeTransform(mv, colorModulator, part.factors, textureMatrix);
            pass.setUniform("DynamicTransforms", dyn);
            pass.setUniform("TvBaseColor", part.baseColor, linear);
            pass.setUniform("TvMetalRough", part.metalRough, linear);
            pass.setUniform("TvNormal", part.normal, linear);
            pass.setUniform("TvEmissive", part.emissive, linear);
            pass.setUniform("TvLightmap", lightmap, clamp);
            pass.setVertexBuffer(0, part.vertexBuffer.slice());
            pass.setIndexBuffer(part.indexBuffer, IndexType.INT);
            pass.drawIndexed(part.mesh.indices.length, 1, 0, 0, 0);
        }
        pass.popDebugGroup();
    }

    /** 角色/道具相对锚点的离地高度与是否自转，由当前模型规格决定。 */
    public float yOffset() {
        return spec != null ? spec.yOffset() : 0f;
    }

    public boolean spins() {
        return spec != null && spec.spin();
    }

    public void close() {
        for (Part part : parts) {
            if (part.vertexBuffer != null) part.vertexBuffer.close();
            if (part.indexBuffer != null) part.indexBuffer.close();
            part.vertexBuffer = null;
            part.indexBuffer = null;
        }
        for (GpuTexture t : ownedTextures) t.close();
        ownedTextures.clear();
        viewByImage.clear();
        solidViews.clear();
        uploaded = false;
    }
}
