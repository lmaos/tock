package com.clmcat.demo;

import java.util.concurrent.ConcurrentHashMap;

public class MapDemo {
    static void main() {

        ConcurrentHashMap<String,String> map = new ConcurrentHashMap<>();

        System.out.println(map.compute("a", (k,v) ->{
            return null;
        }));
        System.out.println(map.compute("a", (k,v) ->{
            return "ss";
        }));

        System.out.println(map.get("a"));
    }
}
