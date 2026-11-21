package tacticalview.fabric;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tacticalview.core.event.EventBus;
import tacticalview.core.lod.LodSelector;

/** 客户端入口。阶段 2 起在这里注册资源加载、实体渲染器、调试面板。 */
public class TacticalViewClient implements ClientModInitializer {
    public static final String MOD_ID = "tacticalview";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    /** 对外公开的事件总线，内容包通过它接入。 */
    public static final EventBus EVENTS = new EventBus();

    @Override
    public void onInitializeClient() {
        // 冒烟检查：确认纯 Java 核心已经一起编进来并能运行
        LodSelector lod = new LodSelector(new float[] {8f, 24f}, 0.1f);
        LOGGER.info("TacticalView client initialized (core OK, {} LOD levels)", lod.levelCount());
    }
}
