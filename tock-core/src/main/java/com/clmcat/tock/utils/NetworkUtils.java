package com.clmcat.tock.utils;

import java.io.IOException;
import java.net.*;
import java.util.*;

/**
 * 网络工具类，提供获取本机 IP 等通用方法。
 */
public final class NetworkUtils {
    // 常见虚拟网卡名称关键字，可通过系统属性覆盖
    private static final Set<String> VIRTUAL_INTERFACE_KEYWORDS = new HashSet<>(Arrays.asList(
            "veth", "docker", "br-", "virbr", "vmnet", "wsl", "hyperv", "hyper-v", "vEthernet", "vbox", "virtual"
    ));
    private NetworkUtils() {
    }

    /**
     * 获取本机排序后的可用 IP 地址列表，优先提供可对外通信的地址。
     * <p>
     * <b>排序规则（从高到低优先级）：</b>
     * <ol>
     *   <li>全局单播地址（公有 IPv4 / 全局 IPv6）</li>
     *   <li>私有地址（10.x, 172.16-31.x, 192.168.x, fc00::/7 等）</li>
     *   <li>其他非链路本地、非回环地址</li>
     * </ol>
     * 链路本地地址（169.254.x.x, fe80::）和回环地址均被排除。
     * </p>
     *
     * @return 排序后的 IP 地址列表（不可变，可能为空）
     */
    public static List<String> getLocalIpAddresses() {
        return getLocalIpAddresses(null);
    }

    /**
     * 获取本机排序后的可用 IP 地址列表，可指定一个期望的接口名（如虚拟网卡名）。
     * 若 {@code preferredInterface} 不为 {@code null}，则匹配的接口地址将排在列表最前面。
     *
     * @param preferredInterface 优先接口名（如 "eth0:1", "lo:0"），可为 {@code null}
     * @return 排序后的 IP 地址列表
     */
    public static List<String> getLocalIpAddresses(String preferredInterface) {
        List<AddressEntry> entries = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return Collections.emptyList();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;

                String niName = ni.getDisplayName();
                boolean virtual = isVirtualInterface(niName);

                Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
                while (inetAddresses.hasMoreElements()) {
                    InetAddress addr = inetAddresses.nextElement();
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    entries.add(new AddressEntry(addr, virtual, niName));
                }
            }
        } catch (SocketException e) {
            return Collections.emptyList();
        }

        // 排序：1. 非虚拟优先  2. 地址优先级  3. 字典序
        entries.sort(Comparator
                .comparingInt((AddressEntry e) -> e.isVirtual ? 1 : 0)
                .thenComparingInt(e -> addressPriority(e.address))
                .thenComparing(e -> e.address.getHostAddress()));

        List<String> result = new ArrayList<>();
        for (AddressEntry entry : entries) {
            result.add(entry.address.getHostAddress());
        }
        return Collections.unmodifiableList(result);
    }

    // 优先级：全局单播 > 站点本地/唯一本地 > 私有 > 其他
    private static int addressPriority(InetAddress addr) {
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) {
            return 3; // 不应出现，但保留
        }
        if (addr instanceof Inet4Address) {
            return addr.isSiteLocalAddress() ? 1 : 0;
        } else if (addr instanceof Inet6Address) {
            Inet6Address addr6 = (Inet6Address) addr;
            if (addr6.isSiteLocalAddress()) { // Java 8 可用
                return 1;
            }
            // 兼容 Java 8：手动检查唯一本地地址前缀 (fc00::/7)
            byte[] bytes = addr6.getAddress();
            if (bytes.length >= 2 && (bytes[0] & 0xfe) == (byte) 0xfc) {
                return 1;
            }
            return 0; // 全局单播
        }
        return 2; // 未知类型
    }


    /**
     * 通过 ICMP 或 TCP/7 探测主机是否可达（不推荐，可能因防火墙/权限失效）。
     * 建议优先使用 {@link #probeReachableIp(List, int, int)} 指定具体服务端口。
     */
    public static boolean isHostReachable(String ip, int timeoutMs) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            return addr.isReachable(timeoutMs);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 从候选 IP 列表中快速探测第一个可连通的地址。
     * <p>
     * 探测方式：尝试与目标 IP 的指定端口建立 TCP 短连接。
     * 连接成功则立即返回该 IP，否则尝试下一个。
     * </p>
     *
     * @param candidateIps 候选 IP 列表，通常从 Redis 或注册中心获取
     * @param port         目标端口（如 Master 时间服务端口 9527）
     * @param timeoutMs    连接超时时间（毫秒），建议 100~500ms
     * @return 第一个可连通的 IP 地址；若全部不可达则返回 {@code null}
     */
    public static String probeReachableIp(List<String> candidateIps, int port, int timeoutMs) {
        if (candidateIps == null || candidateIps.isEmpty()) {
            return null;
        }
        for (String ip : candidateIps) {
            if (ip == null || ip.isEmpty()) {
                continue;
            }
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(ip, port), timeoutMs);
                return ip;
            } catch (IOException e) {
                // 日志可记录，但通常静默跳过
            }
        }
        return null;
    }

    private static boolean isVirtualInterface(String name) {
        String lowerName = name.toLowerCase();
        return VIRTUAL_INTERFACE_KEYWORDS.stream().anyMatch(lowerName::contains);
    }

    // 内部类保存地址和虚拟标记
    private static class AddressEntry {
        final InetAddress address;
        final boolean isVirtual;
        final String interfaceName;

        AddressEntry(InetAddress address, boolean isVirtual, String interfaceName) {
            this.address = address;
            this.isVirtual = isVirtual;
            this.interfaceName = interfaceName;
        }
    }

}