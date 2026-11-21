# tacticalview-content（资产包）

只存放资源与配置，不写任何渲染逻辑；依赖 `tacticalview-core` 提供的加载与渲染能力。
以后要做更多资产包，复制这个目录结构即可。

```
assets/tacticalview_content/
  models/      *.glb（按 LOD 命名：xxx_lod0.glb / xxx_lod1.glb ...）和 *.model.json（模型配置）
  textures/    如果贴图不内嵌在 glb 里，放这里（2K：Albedo / Normal / MetallicRoughness / AO）
  effects/     特效参数（枪口火焰、弹壳、火花），由 socket 的 "effect" 字段引用
```

## 资产标准（核心库的 `AssetValidator` 会逐条检查）

- 格式：glTF 2.0 / GLB。
- 骨骼：Mixamo 标准人形，节点名如 `mixamorig:Hips`、`mixamorig:RightHand`。
- 面数：近距离（LOD0）不超过 40,000 三角形；其余 LOD 逐级递减。
- 贴图：不超过 2048×2048、边长为 2 的幂；每个材质带 Albedo、法线、金属度/粗糙度、AO。
- 动画：片段名在 `*.model.json` 的 `animations` 里映射成逻辑动作（idle/walk/fire/reload ...）。

`models/operator_example.model.json` 是配置格式示例。仓库里不含任何成品模型。
