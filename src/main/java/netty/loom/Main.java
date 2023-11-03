package netty.loom;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.util.ResourceLeakDetector;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class Main {

    private static final boolean RUN_ON_EVENT_LOOP = false;
    private static final int LISTENING_PORT = 8080;
    private static final int EVENT_LOOP_THREADS = Integer.getInteger("eventLoopThreads", 1);
    private static final int LOOM_SCHEDULER_PARALLELISM = 1;

    static {
        ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);
        setupDefaultScheduler(LOOM_SCHEDULER_PARALLELISM);
    }

    public static void main(String[] args) throws InterruptedException {
        verifyLoomThreads(LOOM_SCHEDULER_PARALLELISM);
        try (ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            final EventLoopGroup nettyEventLoopGroup = createNettyEventLoopGroup();
            try {
                InetSocketAddress inetAddress = new InetSocketAddress(LISTENING_PORT);
                ServerBootstrap bootstrap = new ServerBootstrap();
                // IMPORTANT: this seems to be important for MacOS and same for somaxconn!!!
                bootstrap.option(ChannelOption.SO_BACKLOG, 8192);
                bootstrap.option(ChannelOption.SO_REUSEADDR, true);
                bootstrap.group(nettyEventLoopGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel ch) {
                                ch.pipeline().addLast(new HttpServerCodec());
                                // Netty is going to create a new one for each connection
                                ch.pipeline().addLast(new HttpRequestHandler(virtualThreadExecutor, RUN_ON_EVENT_LOOP));
                            }
                        })
                        .childOption(ChannelOption.SO_KEEPALIVE, true)
                        .childOption(ChannelOption.TCP_NODELAY, true)
                        .childOption(ChannelOption.SO_REUSEADDR, true);

                Channel channel = bootstrap.bind(inetAddress)
                        .sync().channel();
                System.out.printf("Http server started. Listening on: %s%n", inetAddress);
                channel.closeFuture().sync();
            } finally {
                nettyEventLoopGroup.shutdownGracefully();
            }
        }
    }

    /**
     * The creation of the default scheduler threads is lazy, so we need to force it and verify.
     */
    private static void verifyLoomThreads(int expectedParallelism) {
        assert !Thread.currentThread().isVirtual();
        try (ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            final AtomicInteger in = new AtomicInteger(0);
            final AtomicInteger out = new AtomicInteger(0);
            final long timeoutInNs = TimeUnit.SECONDS.toNanos(2);
            virtualThreadExecutor.invokeAll(Collections.nCopies(expectedParallelism,
                    () -> {
                        in.incrementAndGet();
                        long start = System.nanoTime();
                        while (in.get() != expectedParallelism) {
                            if (System.nanoTime() - start > timeoutInNs) {
                                return false;
                            }
                        }
                        out.incrementAndGet();
                        return true;
                    })).forEach(future -> {
                try {
                    future.get();
                } catch (Throwable ignore) {

                }
            });
            if (out.get() != expectedParallelism) {
                final String fjPoolThreads = Thread.getAllStackTraces().keySet().stream()
                        .filter(t -> t.getName().startsWith("ForkJoinPool"))
                        .map(Thread::getName)
                        .collect(Collectors.joining(", "));
                System.err.println("Expected " + expectedParallelism + " threads, but got just " + out.get() + " ForkJoinPoolThreads: " + fjPoolThreads);
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This is based on https://github.com/openjdk/jdk/blob/jdk-21%2B35/src/java.base/share/classes/java/lang/VirtualThread.java#L1119-L1141
     */
    private static void setupDefaultScheduler(int parallelism) {
        int maxPoolSize = Integer.max(parallelism, 256);
        int minRunnable = Integer.max(parallelism / 2, 1);
        System.setProperty("jdk.virtualThreadScheduler.parallelism", Integer.toString(parallelism));
        System.setProperty("jdk.virtualThreadScheduler.maxPoolSize", Integer.toString(maxPoolSize));
        System.setProperty("jdk.virtualThreadScheduler.minRunnable", Integer.toString(minRunnable));
    }

    private static EventLoopGroup createNettyEventLoopGroup() {
        final EventLoopGroup nettyEventLoopGroup = new NioEventLoopGroup(EVENT_LOOP_THREADS, new ThreadFactory() {
            private final AtomicInteger threadId = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread nettyThread = new Thread(r, "Netty event-loop-" + threadId.getAndIncrement());
                System.out.println("Created " + nettyThread);
                return nettyThread;
            }
        });
        return nettyEventLoopGroup;
    }
}