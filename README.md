# TacticalView

Minecraft Java 版（Fabric）纯客户端写实模型渲染框架：服务端零修改，原版实体的碰撞、血量、AI、同步全部保留，
只在客户端渲染层叠加/替换视觉效果。

## 目录

```
tacticalview-core/      核心库
  src/main/java/tacticalview/core/
    math/      Mat4、Quat
    json/      零依赖 JSON 解析
    gltf/      GLB/glTF 2.0 解析（网格、蒙皮、PBR 材质引用、图片、动画）
    anim/      Pose、Animator（采样/混合/世界矩阵/关节矩阵）、Skinner（CPU 蒙皮参考实现）
    lod/       LodSelector（距离 + 滞回）
    socket/    Socket / SocketSet（枪口、手部等挂点）
    retarget/  Mixamo 骨骼映射、HumanoidPose、Retargeter（原版姿态 -> 干员骨骼）
    event/     EventBus 与公开事件
    config/    ModelDefinition（*.model.json）
    validate/  ModelStats（面数统计）、AssetValidator（资产标准检查）、ImageInfo
  src/test/java/tacticalview/core/SelfTest.java
tacticalview-content/   资产包模板（只有目录约定与配置示例）
```

## 运行自测（无需联网、无需任何依赖）

```
cd tacticalview-core
mkdir -p /tmp/tv-out
javac -d /tmp/tv-out $(find src -name '*.java')
java -cp /tmp/tv-out tacticalview.core.SelfTest      # 期望输出：ALL OK (92 checks)
```

或 `gradle selfTest`（需要本机有 Gradle）。

## 阶段路线

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 引擎核心：GLB 解析、骨骼动画、蒙皮、LOD、Socket、重定向、资产校验、配置、事件 | 已完成，自测通过 |
| 2 | Fabric 适配层：模组入口、资源包加载 `.glb`、GPU 上传（VBO/纹理）、PBR + 蒙皮着色器、替换原版实体渲染 | 待做 |
| 3 | 第一人称持枪：手部/武器渲染、位置微调、瞄准/开火/换弹动画状态机 | 待做 |
| 4 | 光照对接：方块光/天空光/雾/阴影；LOD 接入渲染 | 待做 |
| 5 | 特效 Socket 触发、调试面板（偏移/缩放/光照/LOD/动画预览/面数） | 待做 |

## 关于阶段 2 的说明

阶段 2 之后的代码必须针对具体的 Minecraft 版本编译。26.x 的渲染管线较 1.21.5 之前有大改，
所以适配层要在你的开发环境里（IntelliJ + JDK 25 + Fabric Loom）对着真实 API 编写和调试。
核心库与 Minecraft 完全解耦，版本升级时只需要改适配层。
