package tacticalview.core.event;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** 轻量事件总线：内容包/其他模组通过它接入渲染流程（例如挂点触发特效）。线程安全。 */
public final class EventBus {
    private static final class Entry<E> {
        final Class<E> type;
        final Consumer<? super E> handler;

        Entry(Class<E> type, Consumer<? super E> handler) {
            this.type = type;
            this.handler = handler;
        }
    }

    private final List<Entry<?>> entries = new CopyOnWriteArrayList<>();

    public <E> Runnable register(Class<E> type, Consumer<? super E> handler) {
        Entry<E> e = new Entry<>(type, handler);
        entries.add(e);
        return () -> entries.remove(e);
    }

    @SuppressWarnings("unchecked")
    public <E> int post(E event) {
        int n = 0;
        for (Entry<?> e : new ArrayList<>(entries)) {
            if (e.type.isInstance(event)) {
                ((Consumer<Object>) e.handler).accept(event);
                n++;
            }
        }
        return n;
    }
}
