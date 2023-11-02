package netty.loom;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;

import java.util.concurrent.ExecutorService;

public class HttpRequestHandler extends ChannelInboundHandlerAdapter {

    private static final boolean USE_POOLED_DIRECT_BUFFERS = false;
    private final ExecutorService blockingThreadPool;
    private final boolean runOnEventLoop;
    private ChannelHandlerContext ctx;

    public HttpRequestHandler(ExecutorService blockingThreadPool, boolean runOnEventLoop) {
        this.blockingThreadPool = blockingThreadPool;
        this.runOnEventLoop = runOnEventLoop;
    }


    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.ctx = ctx;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!canCallService(msg)) {
            return;
        }
        if (runOnEventLoop) {
            callServiceAndFlushResponse();
        } else {
            blockingThreadPool.execute(this::callServiceAndFlushResponse);
        }
    }

    // Netty boilerplate stuff: we do not accept anything but non-chunked HTTP GET requests
    private boolean canCallService(Object msg) {
        if (msg == LastHttpContent.EMPTY_LAST_CONTENT) {
            return false;
        }
        if (msg.getClass() != DefaultHttpRequest.class) {
            sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, true);
            return false;
        }
        final DefaultHttpRequest request = (DefaultHttpRequest) msg;
        if (request.method() != HttpMethod.GET) {
            sendErrorResponse(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, false);
            return false;
        }
        return true;
    }

    private void callServiceAndFlushResponse() {
        CharSequence serviceResponse = serviceCall();
        if (ctx.executor().inEventLoop()) {
            ByteBuf response = encodeAsciiResponse(ctx, serviceResponse, USE_POOLED_DIRECT_BUFFERS);
            writeAndFlushResponse(ctx, response);
        } else {
            ctx.executor().execute(() -> {
                ByteBuf response = encodeAsciiResponse(ctx, serviceResponse, USE_POOLED_DIRECT_BUFFERS);
                writeAndFlushResponse(ctx, response);
            });
        }
    }

    // TODO for Andrew H.: place here your blocking service call, which can use
    //      a reference to an external provided Service's API
    private CharSequence serviceCall() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {

        }
        String resultOfBlockingComputation = "Hello World!";
        return resultOfBlockingComputation;
    }

    private static ByteBuf encodeAsciiResponse(ChannelHandlerContext ctx, CharSequence content, boolean usePooledDirect) {
        if (usePooledDirect) {
            ByteBuf buffer = ctx.alloc().directBuffer(content.length());
            buffer.writeCharSequence(content, CharsetUtil.US_ASCII);
            return buffer;
        } else {
            return Unpooled.copiedBuffer(content, CharsetUtil.US_ASCII);
        }
    }

    /**
     * This method is safe to be called from any thread, but in our case we are calling it from the event loop thread, by
     * dispatching it from the blocking thread pool.<br>
     * <p>
     * In quarkus/vertx, in case a blocking executor is used, we:
     * 1. allocate a pooled Netty buffer which contains the encoded response (in JSON, for example, is very common)
     * 2. instruct Vertx to write it to the channel via https://github.com/eclipse-vertx/vert.x/blob/4.4.6/src/main/java/io/vertx/core/http/impl/Http1xServerResponse.java#L432,
     * wrapping it in a FullHttpResponse (which reference the mentioned pooled Netty buffer)
     * 3. Vertx will then schedule a Runnable on the event loop thread which will write and flush the response to the Netty channel
     * (see https://github.com/eclipse-vertx/vert.x/blob/4.4.6/src/main/java/io/vertx/core/http/impl/Http1xServerResponse.java#L432)
     * <p>
     * The infamous deadlocking problem, reported https://github.com/mariofusco/loom-experiments/blob/main/src/main/java/org/mfusco/loom/experiments/LoomDeadlockMain.java
     * and https://github.com/netty/netty/issues/13204 is "triggered" by 1 and 3 (+ others), because:
     * - In 1, the virtual thread executor can interact with a lock on the jemalloc arenas which the buffer allocator belong to
     * - In 3, Netty will release back the buffer in the FullHttpResponse to the arenas, interacting again with the same lock
     * <p>
     * In addition, to further increase the chance of deadlocks, even Netty itself while reading data out of the network
     * allocates direct pooled buffers using the same allocator and could interact with the same arena(s).
     */
    private static void writeAndFlushResponse(ChannelHandlerContext ctx, ByteBuf contentBytes) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, contentBytes);
        response.headers()
                .set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN)
                // autoboxing + String generation on Integer.toString
                .set(HttpHeaderNames.CONTENT_LENGTH, contentBytes.readableBytes());
        ctx.writeAndFlush(response, ctx.voidPromise());
    }


    private static void sendErrorResponse(ChannelHandlerContext ctx, HttpResponseStatus status, boolean close) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN);

        response.content().writeBytes(("Error: " + status).getBytes());
        if (close) {
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } else {
            ctx.writeAndFlush(response, ctx.voidPromise());
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }
}
