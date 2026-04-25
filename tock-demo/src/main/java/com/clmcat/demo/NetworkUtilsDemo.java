package com.clmcat.demo;

import com.clmcat.tock.utils.NetworkUtils;

public class NetworkUtilsDemo {

    static void main() {
            System.out.println("本机 IP 地址列表：");
        NetworkUtils.getLocalIpAddresses().forEach(System.out::println);
    }
}
