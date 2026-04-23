package com.clmcat.tock.utils;

import com.clmcat.tock.Config;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ReferenceSupport {

    private final static Set<Config> configReferenceMap = ConcurrentHashMap.newKeySet();

    private final static Set<Object> commonReferenceMap = ConcurrentHashMap.newKeySet();

    public static boolean configReference(Config config) {

        return configReferenceMap.add(config);
    }
    public static boolean configRemoveReference(Config config) {
        return configReferenceMap.remove(config);
    }
    public static boolean commonReference(Object common) {
        return commonReferenceMap.add(common);
    }

    public static boolean commonRemoveReference(Object common) {
        return commonReferenceMap.remove(common);
    }

}
