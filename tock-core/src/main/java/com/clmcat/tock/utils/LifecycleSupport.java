package com.clmcat.tock.utils;

import com.clmcat.tock.Lifecycle;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.util.*;

/**
 * 生命周期自动排序工具。
 * <p>
 * 通过反射递归发现 {@link Lifecycle} 组件及其字段中持有的其他 {@code Lifecycle} 依赖，
 * 按照“深层依赖优先、当前组件在后”的原则生成有序列表。
 * </p>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * List<Lifecycle> ordered = LifecycleSupport.loadLifecycles(topLevelComponents);
 * // 启动（正序）
 * for (Lifecycle lc : ordered) {
 *     lc.start(context);
 * }
 * // 停止（倒序）
 * for (int i = ordered.size() - 1; i >= 0; i--) {
 *     ordered.get(i).stop();
 * }
 * }</pre>
 *
 * <h3>重要约束</h3>
 * 传入的集合中的顶层组件<b>必须实现 {@link Lifecycle} 接口</b>，否则将被忽略。
 * 这一约束是设计使然：如果某个组件本身不需要生命周期管理，那么即便其内部
 * 持有 {@code Lifecycle} 字段，也应由其外层 {@code Lifecycle} 容器负责管理，
 * 框架不应绕过外层直接控制内部组件。
 *
 * <h3>排序规则</h3>
 * <ul>
 *   <li>顶层组件按传入集合的迭代顺序依次处理。</li>
 *   <li>每个组件内部的 {@code Lifecycle} 字段按深度优先递归，同层字段按声明顺序。</li>
 *   <li>同一 {@code Lifecycle} 实例在最终列表中只会出现一次。</li>
 * </ul>
 *
 * @author clmcat
 * @see Lifecycle
 */
@Slf4j
public final class LifecycleSupport {

    private LifecycleSupport() {}

    /**
     * 从一组组件中提取所有 Lifecycle 并返回按依赖关系排序的列表（内层依赖在前）。
     *
     * @param components 可能包含 Lifecycle 的组件集合
     * @return 排好序的 Lifecycle 列表（无重复）
     */
    public static List<Lifecycle> loadLifecycles(Collection<Object> components) {
        Set<Lifecycle> visited = new HashSet<>();
        List<Lifecycle> result = new ArrayList<>();
        for (Object component : components) {
            if (component instanceof Lifecycle) {
                result.addAll(loadLifecycles((Lifecycle) component, visited));
            }
        }
        return result;
    }

    /**
     * 递归获取一个 Lifecycle 及其所有字段依赖的排序列表。
     *
     * @param component 当前组件
     * @param visited   已访问过的 Lifecycle 集合（用于去重）
     * @return 当前组件及其子依赖的排序列表（无重复）
     */
    private static List<Lifecycle> loadLifecycles(Lifecycle component, Set<Lifecycle> visited) {
        if (!visited.add(component)) {
            return Collections.emptyList();   // 已处理过，避免重复
        }

        List<Lifecycle> result = new ArrayList<>();

        // 1. 先处理所有字段（依赖）
        for (Lifecycle dep : findLifecycleFields(component)) {
            result.addAll(loadLifecycles(dep, visited));  // 递归添加子依赖
        }

        // 2. 最后添加当前组件（保证依赖优先启动）
        result.add(component);
        return result;
    }

    /**
     * 通过反射获取 component 中所有 Lifecycle 类型的字段值。
     */
    private static List<Lifecycle> findLifecycleFields(Lifecycle component) {
        List<Lifecycle> deps = new ArrayList<>();
        Class<?> clazz = component.getClass();
        while (clazz != null && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Lifecycle.class.isAssignableFrom(field.getType())) {
                    try {
                        field.setAccessible(true);
                        Object value = field.get(component);
                        if (value instanceof Lifecycle) {
                            deps.add((Lifecycle) value);
                        }
                    } catch (Exception e) {
                        log.debug("Unable to access field '{}' on {}: {}",
                                field.getName(), clazz.getSimpleName(), e.getMessage());
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return deps;
    }
}