package com.clmcat.tock.health.client;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 绝对顺序读数据包， 1写 1读。
 */
@Slf4j
public class HealthClient {


    private static ScheduledExecutorService taks =  Executors.newSingleThreadScheduledExecutor();

    private Semaphore semaphore = new Semaphore(256);
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private String host;
    private int port;

    private AtomicBoolean started = new AtomicBoolean(false);
    private ExecutorService reader;
    private ExecutorService writer;




    private AtomicInteger counter = new AtomicInteger(0);

    private Map<Integer, RequestContext> requests = new ConcurrentHashMap<>();


    private LinkedBlockingQueue<RequestContext> writeQueue = new LinkedBlockingQueue<>();

    public HealthClient(String host, int port) {
        this.host = host;
        this.port = port;
    }


    public void start() throws IOException {

        if (started.compareAndSet(false, true)) {
            try {
                socket = new Socket(host, port);
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                socket.setReuseAddress(true);
                socket.setSendBufferSize(4096);
                socket.setReceiveBufferSize(4096);
                socket.setTrafficClass(2);
                this.out = socket.getOutputStream();
                this.in = socket.getInputStream();
            } catch (IOException e) {
                log.error("connect to health server error", e);
                started.set(false);
                onConnectionFailed();
                throw e;
            }

            reader = Executors.newSingleThreadExecutor(r ->{
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("health-client-reader-thread");
                return t;
            });
            writer = Executors.newSingleThreadExecutor(r ->{
                Thread t = new Thread(r);
                t.setDaemon(true);
                t.setName("health-client-write-thread");
                return t;
            });
        }
        reader.submit(this::readerHandler);
        writer.submit(this::writeHandler);
    }

    // 顺序写

    private void writeHandler() {
        while (started.get()) {
            try  {
                RequestContext poll = writeQueue.poll(1, TimeUnit.SECONDS);
                if (poll != null) {
                    out.write(poll.getRequestData());
                    out.flush();
                }
            } catch (Exception e) {
                log.error("write thread error", e);
            }
        }
    }
    // 顺序读
    private void readerHandler() {

        DataInputStream dis = new  DataInputStream(new BufferedInputStream(in));

        try {
            while (started.get()) {
                try  {
                    int reqId = dis.readInt();
                    byte code = dis.readByte();
                    byte type = dis.readByte();
                    long time =  dis.readLong();

                    HealthResponse response = HealthResponse.builder()
                            .reqId(reqId) // 4
                            .code(code)  // 1
                            .type(type)  // 1
                            .time(time)  // 8
                            .build();
                    RequestContext requestContext = requests.remove(reqId);
                    if (requestContext != null) {
                        requestContext.setResponse(response);
                    }
                    if (code != 0) {
                        break;
                    }
                } catch (EOFException e) {
                    log.info("Server closed connection");
                    break;
                } catch (SocketException e) {
                    log.info("Connection reset");
                    break;
                } catch (IOException e) {
                    log.error("IO error in reader", e);
                    break;
                }
            }
        } finally {
            stop();
        }
    }

    public void stop()  {
        if (started.compareAndSet(true, false)) {
            try {
                socket.close();
            } catch (IOException e) {
                log.error("close socket error", e);
            }
            onConnectionBroken();
            reader.shutdownNow();
            writer.shutdownNow();
            requests.values().forEach(ctx -> ctx.setResponse(HealthResponse.TIMEOUT));
            requests.clear();
        }
    }


    public HealthResponse request(HealthRequest request, int readTimeout) throws InterruptedException, TimeoutException {
        semaphore.acquire();
        int reqId = counter.incrementAndGet();
        try {
            byte[] bytes = request.toBytes(reqId);
            RequestContext requestContext = new RequestContext(bytes);
            requests.put(reqId, requestContext);
            writeQueue.add(requestContext);
            return requestContext.response(readTimeout);
        } finally {
            // 移除上下文。
            requests.remove(reqId);
            semaphore.release();
        }
    }


    public long serverTime() {
        try {
            HealthRequest request = new HealthRequest(0,0);
            HealthResponse response = request(request, 5000);
            if (response != null && response.getCode() == 0) {
                return response.getTime();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("request thread interrupted", e);
            return -1;
        } catch (TimeoutException e) {
            log.error("request timeout error", e);
            return -1;
        }
        return -1;
    }

    public HealthResponse reportActive(String nodeId) {
        try {
            HealthReportActiveRequest request = new HealthReportActiveRequest(nodeId);
            HealthResponse response = request(request, 5000);

            return response ==  null ? HealthResponse.NOT_FOUND : response;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("request thread interrupted", e);
            return HealthResponse.SYSTEM_ERROR;
        } catch (TimeoutException e) {
            log.error("request timeout error", e);
            return HealthResponse.TIMEOUT;
        }

    }

    public boolean isStarted() {
        return started.get();
    }
    // 链接建立失败
    protected void onConnectionFailed() {

    }
    // 连接中断
    protected void onConnectionBroken() {

    }
}
