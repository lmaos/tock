package com.clmcat.tock.utils;

import com.clmcat.tock.Lifecycle;

import java.lang.reflect.Field;
import java.util.*;

public class LifecycleSupport {

    public static List<Lifecycle> loaderLifecycles(Collection<Object> components) {
        List<Lifecycle> lifecycles = new LinkedList<>();
        components.forEach(component -> {
            if (component instanceof Lifecycle) {
                Lifecycle lifecycle = (Lifecycle) component;
                List<Lifecycle> items = loaderLifecycles(lifecycle, new HashSet<>());
                lifecycles.addAll(items);
            }
        });
        return lifecycles;
    }

    public static List<Lifecycle> loaderLifecycles(Lifecycle component, Set<Object> components) {

        LinkedList<Lifecycle> list = new LinkedList<Lifecycle>();

//        if (!components.add(component)) {
//            return list;
//        }

        list.add(component);

        List<Lifecycle> fields = findFields(component);
        for (Lifecycle lifecycle : fields) {
            List<Lifecycle> lifecycles = loaderLifecycles(lifecycle, components);
            Collections.reverse(lifecycles);
            for (Lifecycle item : lifecycles) {
                if (components.add(item)) {
                    list.addFirst(item);
                }
            }
        }
        return list;
    }

    private static List<Lifecycle> findFields(Lifecycle component) {
        Class<?> clazz = component.getClass();
        List<Lifecycle> list = new ArrayList<>();
        while (clazz != null &&  clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (Lifecycle.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        Object object = field.get(component);
                        if (object != null) {
                            list.add((Lifecycle) object);
                        }
                    } catch (Exception e) {

                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return list;
    }

}
