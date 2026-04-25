package com.clmcat.tock.health.server;

import com.clmcat.tock.health.HealthHost;
import com.clmcat.tock.utils.NetworkUtils;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class HealthServer {
    private final String key = UUID.randomUUID().toString();
    @Getter
    private int port;
    private ExecutorService bossExecutor;
    private ExecutorService workerExecutor;
    private AtomicBoolean started = new AtomicBoolean(false);

    private Selector bossSelector;
    private Selector workerSelectors[];
    private ServerSocketChannel serverSocketChannel;
    private int workerPoolSize;
    private final SelectorBalancer selectorBalancer;


    public HealthServer(int port) {
        this(port, 2);
    }

    public HealthServer(int port, int workerPoolSize) {
        this.port = port;
        this.workerPoolSize = workerPoolSize;
        this.selectorBalancer = new DefaultSelectorBalancer(workerPoolSize);
        this.workerSelectors = new Selector[workerPoolSize];
    }

    public HealthHost getHealthHost() {
        HealthHost healthHost = new HealthHost();
        healthHost.setPort(port);
        healthHost.setHosts(NetworkUtils.getLocalIpAddresses());
        healthHost.setKey(key);
        return healthHost;
    }

    public HealthHost start() throws IOException {

        if (started.compareAndSet(false, true)) {
            this.bossSelector = Selector.open();
            for (int i = 0; i < workerSelectors.length; i++) {
                workerSelectors[i] = Selector.open();
            }
            this.serverSocketChannel = ServerSocketChannel.open();
            this.serverSocketChannel.configureBlocking(false);
            this.serverSocketChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
            this.serverSocketChannel.bind(new java.net.InetSocketAddress(port), 10000);
            this.serverSocketChannel.register(bossSelector, serverSocketChannel.validOps());

            if (port == 0) {
                this.port = this.serverSocketChannel.socket().getLocalPort();
            }

            bossExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("health-server-boss-thread");
                return t;
            });
            workerExecutor = Executors.newFixedThreadPool(workerPoolSize, r -> {
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("health-server-worker-thread");
                return t;
            });
            log.info("Server started on port {}", port);


            bossExecutor.submit(() -> {
                accept(bossSelector, workerSelectors);
            });
            for (int i = 0; i < workerPoolSize; i++) {
                Selector workerSelector = workerSelectors[i];
                workerExecutor.submit(() -> {
                    log.info("Worker thread entered ready()");
                    ready(workerSelector);
                });
            }
        }
        return getHealthHost();
    }


    private void accept(Selector bossSelector, Selector[] workerSelectors) {
        while (started.get()) {
            try {
                bossSelector.select(1000);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                Set<SelectionKey> keys = bossSelector.selectedKeys();
                Iterator<SelectionKey> iter = keys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    if (key.isAcceptable()) {
                        SocketChannel socketChannel = serverSocketChannel.accept();
                        if (socketChannel != null) {
                            Selector workerSelector = workerSelectors[selectorBalancer.next()];
                            socketChannel.configureBlocking(false);
                            // 开启 TCP_NODELAY，提高小包响应速度
                            socketChannel.setOption(StandardSocketOptions.TCP_NODELAY, true);
                            socketChannel.register(workerSelector, SelectionKey.OP_READ);
                            workerSelector.wakeup();

                            log.info("Socket channel accepted : {}", socketChannel.getRemoteAddress());
                        }
                    }
                }
            } catch (IOException e)  {
                log.error("Error accepting socket channel", e);
            }
        }
    }


    private void ready(Selector workerSelector) {
        while (started.get()) {
            try {
                int count = workerSelector.select(1000);
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                Set<SelectionKey> keys = workerSelector.selectedKeys();
                Iterator<SelectionKey> iter = keys.iterator();
                while (iter.hasNext()) {
                    SelectionKey key = iter.next();
                    iter.remove();
                    try {
                        ChannelContext channelContext = (ChannelContext) key.attachment();
                        if (channelContext == null) {
                            // 单次数据包 最大 4096
                            channelContext = new ChannelContext(ByteBuffer.allocate(4096).order(ByteOrder.BIG_ENDIAN));
                            key.attach(channelContext);
                        }
                        ByteBuffer readBuffer = channelContext.readBuffer;

                        SocketChannel socketChannel = (SocketChannel) key.channel();


                        if (key.isReadable()) {
                            log.info("Socket channel read : {}", socketChannel.getRemoteAddress());
                            int len = socketChannel.read(readBuffer);
                            if (len <= 0) {
                                if (len == -1) {
                                    socketChannel.close();
                                    key.cancel();
                                    log.info("Socket channel closed : {}", socketChannel.getRemoteAddress());
                                }
                                continue; // 跳过 本次 while 的 Channel 处理
                            }
                            // 切换都
                            try {
                                readBuffer.flip();
                                // 协议：4字节请求ID + 1字节code + 1字节type = 6字节
                                while (readBuffer.remaining() >= 6) {
                                    readBuffer.mark(); // 标记当前 position，以便后续重置
                                    int reqId = readBuffer.getInt();
                                    byte code = readBuffer.get();
                                    byte type = readBuffer.get(); // 数据类型，暂不使用

                                    // type == 1 携带 clientId 标识。
                                    if (type == 1) {
                                        // 携带了一个数据. 2字节 长度 + text
                                        int length = readBuffer.getShort();
                                        if (readBuffer.remaining() < length) {
                                            readBuffer.reset();
                                            break;
                                        }
                                        byte[] data = new byte[length];
                                        readBuffer.get(data);
                                        channelContext.clientId = new String(data).trim();
                                        if (channelContext.clientId != null && !channelContext.clientId.isEmpty()) {
                                            onActive(channelContext);
                                        }
                                    }


                                    // 构建写数据Buffer
                                    ByteBuffer writeBuffer = ByteBuffer.allocate(128).order(ByteOrder.BIG_ENDIAN);
                                    // 统一写入消息ID
                                    writeBuffer.putInt(reqId);
                                    if (code == 0) {
                                        writeBuffer.put((byte) 0); // 状态码，0 表示成功并允许保持，1 表示成功但不允许保持， -1 要求客户端关闭
                                        writeBuffer.put((byte) 0); // 数据 0: 时间戳 (8bit), 1: text
                                        writeBuffer.putLong(System.currentTimeMillis());
                                    } else {
                                        writeBuffer.put((byte) -1); // 状态码，0 表示成功并允许保持，1 表示成功但不允许保持， -1 要求客户端关闭
                                        writeBuffer.put((byte) 0); // 数据 0: 时间戳 (8bit), 1: text
                                        writeBuffer.putLong(System.currentTimeMillis());
                                    }





                                    writeBuffer.flip();


                                    boolean needWrite = channelContext.writeQueue.isEmpty(); // 入队前队列为空才需要注册写事件
                                    channelContext.writeQueue.offer(writeBuffer);
                                    if (needWrite) {
                                        key.interestOps(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
                                    }

                                    if (code != 0) {
                                        channelContext.markForClose = true;  // 关闭
                                        key.interestOps(SelectionKey.OP_WRITE);
                                    }
                                }
                            } finally {
                                // 切换为写模式，准备下一次读取
                                readBuffer.compact();
                            }
                        } else if (key.isWritable()) {
                            ByteBuffer writeBuffer = channelContext.writeQueue.peek();
                            if (writeBuffer != null) {
                                socketChannel.write(writeBuffer);
                                if (!writeBuffer.hasRemaining()) {
                                    channelContext.writeQueue.poll(); // 移除已写完的 Buffer
                                }
                            }

                            if (channelContext.writeQueue.isEmpty()) {
                                if (channelContext.markForClose) {
                                    socketChannel.close();
                                    key.cancel();
                                    continue;
                                }

                                key.interestOps(SelectionKey.OP_READ);

                            }

                        }
                    } catch (ClosedChannelException e) {
                        log.debug("Client closed connection early: {}", e.getMessage());
                        key.cancel();   // 取消注册，避免 Selector 持续轮询已关闭通道
                    } catch (IOException e) {
                        log.error("Error handling socket channel", e);
                        try {
                            key.cancel();
                            key.channel().close();
                        } catch (IOException ex) {
                            log.error("Error closing socket channel", ex);
                        }
                    }
                }
            } catch (Exception e) {
                log.error("Error accepting socket channel", e);
            }
        }
    }

    public void stop() {
        if (started.compareAndSet(true, false)) {
            try {
                bossExecutor.shutdownNow();
                workerExecutor.shutdownNow();
                serverSocketChannel.close();
                for (int i = 0; i < workerSelectors.length; i++) {
                    workerSelectors[i].close();
                }
                bossSelector.close();
            } catch (IOException e) {}
        }
    }

    protected void onActive(ChannelContext channelContext) {
        String clientId = channelContext.getClientId();
        log.info("Node {} has been activated", clientId);
    }

    public static class ChannelContext {
        private final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();
        private final ByteBuffer readBuffer;
        private boolean markForClose = false;

        public ChannelContext(ByteBuffer readBuffer) {
            this.readBuffer = readBuffer;
        }

        @Getter
        private String clientId;
    }
}
