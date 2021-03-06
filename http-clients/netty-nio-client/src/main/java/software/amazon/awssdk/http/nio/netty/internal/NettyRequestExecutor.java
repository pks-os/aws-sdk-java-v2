/*
 * Copyright 2010-2018 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.awssdk.http.nio.netty.internal;

import static software.amazon.awssdk.http.Protocol.HTTP1_1;
import static software.amazon.awssdk.http.Protocol.HTTP2;
import static software.amazon.awssdk.http.nio.netty.internal.ChannelAttributeKey.EXECUTE_FUTURE_KEY;
import static software.amazon.awssdk.http.nio.netty.internal.ChannelAttributeKey.EXECUTION_ID_KEY;
import static software.amazon.awssdk.http.nio.netty.internal.ChannelAttributeKey.REQUEST_CONTEXT_KEY;
import static software.amazon.awssdk.http.nio.netty.internal.ChannelAttributeKey.RESPONSE_COMPLETE_KEY;

import com.typesafe.netty.http.HttpStreamsClientHandler;
import com.typesafe.netty.http.StreamedHttpRequest;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutException;
import io.netty.handler.timeout.WriteTimeoutHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.annotations.SdkInternalApi;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.nio.netty.internal.http2.Http2ToHttpInboundAdapter;
import software.amazon.awssdk.http.nio.netty.internal.http2.HttpToHttp2OutboundAdapter;
import software.amazon.awssdk.http.nio.netty.internal.utils.ChannelUtils;

@SdkInternalApi
public final class NettyRequestExecutor {
    private static final Logger log = LoggerFactory.getLogger(NettyRequestExecutor.class);
    private static final RequestAdapter REQUEST_ADAPTER = new RequestAdapter();
    private static final AtomicLong EXECUTION_COUNTER = new AtomicLong(0L);
    private final long executionId = EXECUTION_COUNTER.incrementAndGet();
    private final RequestContext context;
    private CompletableFuture<Void> executeFuture;
    private Channel channel;

    public NettyRequestExecutor(RequestContext context) {
        this.context = context;
    }

    @SuppressWarnings("unchecked")
    public CompletableFuture<Void> execute() {
        Future<Channel> channelFuture = context.channelPool().acquire();
        executeFuture = createExecuteFuture(channelFuture);
        channelFuture.addListener((GenericFutureListener) this::makeRequestListener);
        return executeFuture;
    }

    /**
     * Convenience method to create the execution future and set up the cancellation logic.
     *
     * @param channelFuture The Netty future holding the channel.
     *
     * @return The created execution future.
     */
    private CompletableFuture<Void> createExecuteFuture(Future<Channel> channelFuture) {
        final CompletableFuture<Void> future = new CompletableFuture<>();

        future.whenComplete((r, t) -> {
            if (t == null) {
                return;
            }

            channelFuture.addListener((Future<Channel> f) -> {
                if (!f.isSuccess()) {
                    return;
                }

                Channel ch = f.getNow();
                ch.eventLoop().submit(() -> {
                    ch.pipeline().fireExceptionCaught(new FutureCancelledException(executionId, t));
                });
            });
        });

        return future;
    }

    private void makeRequestListener(Future<Channel> channelFuture) {
        if (channelFuture.isSuccess()) {
            channel = channelFuture.getNow();
            configureChannel();
            configurePipeline();
            makeRequest();
        } else {
            handleFailure(() -> "Failed to create connection to " + endpoint(), channelFuture.cause());
        }
    }

    private void configureChannel() {
        channel.attr(EXECUTION_ID_KEY).set(executionId);
        channel.attr(EXECUTE_FUTURE_KEY).set(executeFuture);
        channel.attr(REQUEST_CONTEXT_KEY).set(context);
        channel.attr(RESPONSE_COMPLETE_KEY).set(false);
        channel.config().setOption(ChannelOption.AUTO_READ, false);
    }

    private void configurePipeline() {
        Protocol protocol = ChannelAttributeKey.getProtocolNow(channel);
        ChannelPipeline pipeline = channel.pipeline();
        if (HTTP2.equals(protocol)) {
            pipeline.addLast(new Http2ToHttpInboundAdapter());
            pipeline.addLast(new HttpToHttp2OutboundAdapter());
        } else if (!HTTP1_1.equals(protocol)) {
            String errorMsg = "Unknown protocol: " + protocol;
            closeAndRelease(channel);
            handleFailure(() -> errorMsg, new RuntimeException(errorMsg));
            return;
        }
        pipeline.addLast(new HttpStreamsClientHandler());
        pipeline.addLast(new ResponseHandler());
    }

    private void makeRequest() {
        HttpRequest request = REQUEST_ADAPTER.adapt(context.executeRequest().request());
        writeRequest(request);
    }

    private void writeRequest(HttpRequest request) {
        channel.pipeline().addFirst(new WriteTimeoutHandler(context.configuration().writeTimeoutMillis(),
                                                            TimeUnit.MILLISECONDS));
        StreamedRequest streamedRequest = new StreamedRequest(request,
                                                              context.executeRequest().requestContentPublisher(),
                                                              channel);
        channel.writeAndFlush(streamedRequest)
               .addListener(wireCall -> {
                   // Done writing so remove the idle write timeout handler
                   ChannelUtils.removeIfExists(channel.pipeline(), WriteTimeoutHandler.class);
                   if (wireCall.isSuccess()) {
                       if (!context.executeRequest().fullDuplex()) {
                           // Starting read so add the idle read timeout handler, removed when channel is released
                           channel.pipeline().addFirst(new ReadTimeoutHandler(context.configuration().readTimeoutMillis(),
                                                                              TimeUnit.MILLISECONDS));
                           channel.read();
                       }
                   } else {
                       // TODO: Are there cases where we can keep the channel open?
                       closeAndRelease(channel);
                       handleFailure(() -> "Failed to make request to " + endpoint(), wireCall.cause());
                   }
               });

        // FullDuplex calls need to start reading at the same time we make the request.
        if (context.executeRequest().fullDuplex()) {
            channel.pipeline().addFirst(new ReadTimeoutHandler(context.configuration().readTimeoutMillis(),
                                                               TimeUnit.MILLISECONDS));
            channel.read();
        }
    }

    private URI endpoint() {
        return context.executeRequest().request().getUri();
    }

    private void handleFailure(Supplier<String> msg, Throwable cause) {
        log.error(msg.get(), cause);
        cause = decorateException(cause);
        context.handler().onError(cause);
        executeFuture.completeExceptionally(cause);
    }

    private Throwable decorateException(Throwable originalCause) {
        if (isAcquireTimeoutException(originalCause)) {
            return new Throwable(getMessageForAcquireTimeoutException(), originalCause);
        } else if (isTooManyPendingAcquiresException(originalCause)) {
            return new Throwable(getMessageForTooManyAcquireOperationsError(), originalCause);
        } else if (originalCause instanceof ReadTimeoutException) {
            return new IOException("Read timed out", originalCause);
        } else if (originalCause instanceof WriteTimeoutException) {
            return new IOException("Write timed out", originalCause);
        }

        return originalCause;
    }

    private boolean isAcquireTimeoutException(Throwable originalCause) {
        return originalCause instanceof TimeoutException && originalCause.getMessage().contains("Acquire operation took longer");
    }

    private boolean isTooManyPendingAcquiresException(Throwable originalCause) {
        return originalCause instanceof IllegalStateException &&
               originalCause.getMessage().contains("Too many outstanding acquire operations");
    }

    private String getMessageForAcquireTimeoutException() {
        return "Acquire operation took longer than the configured maximum time. This indicates that a request cannot get a "
                + "connection from the pool within the specified maximum time. This can be due to high request rate.\n"

                + "Consider taking any of the following actions to mitigate the issue: increase max connections, "
                + "increase acquire timeout, or slowing the request rate.\n"

                + "Increasing the max connections can increase client throughput (unless the network interface is already "
                + "fully utilized), but can eventually start to hit operation system limitations on the number of file "
                + "descriptors used by the process. If you already are fully utilizing your network interface or cannot "
                + "further increase your connection count, increasing the acquire timeout gives extra time for requests to "
                + "acquire a connection before timing out. If the connections doesn't free up, the subsequent requests "
                + "will still timeout.\n"

                + "If the above mechanisms are not able to fix the issue, try smoothing out your requests so that large "
                + "traffic bursts cannot overload the client, being more efficient with the number of times you need to "
                + "call AWS, or by increasing the number of hosts sending requests.";
    }

    private String getMessageForTooManyAcquireOperationsError() {
        return "Maximum pending connection acquisitions exceeded. The request rate is too high for the client to keep up.\n"

                + "Consider taking any of the following actions to mitigate the issue: increase max connections, "
                + "increase max pending acquire count, decrease pool lease timeout, or slowing the request rate.\n"

                + "Increasing the max connections can increase client throughput (unless the network interface is already "
                + "fully utilized), but can eventually start to hit operation system limitations on the number of file "
                + "descriptors used by the process. If you already are fully utilizing your network interface or cannot "
                + "further increase your connection count, increasing the pending acquire count allows extra requests to be "
                + "buffered by the client, but can cause additional request latency and higher memory usage. If your request"
                + " latency or memory usage is already too high, decreasing the lease timeout will allow requests to fail "
                + "more quickly, reducing the number of pending connection acquisitions, but likely won't decrease the total "
                + "number of failed requests.\n"

                + "If the above mechanisms are not able to fix the issue, try smoothing out your requests so that large "
                + "traffic bursts cannot overload the client, being more efficient with the number of times you need to call "
                + "AWS, or by increasing the number of hosts sending requests.";
    }

    /**
     * Close and release the channel back to the pool.
     *
     * @param channel The channel.
     */
    private void closeAndRelease(Channel channel) {
        log.trace("closing and releasing channel {}", channel.id().asLongText());
        channel.close().addListener(ignored -> context.channelPool().release(channel));
    }

    /**
     * Just delegates to {@link HttpRequest} for all methods.
     */
    static class DelegateHttpRequest implements HttpRequest {
        protected final HttpRequest request;

        DelegateHttpRequest(HttpRequest request) {
            this.request = request;
        }

        @Override
        public HttpRequest setMethod(HttpMethod method) {
            this.request.setMethod(method);
            return this;
        }

        @Override
        public HttpRequest setUri(String uri) {
            this.request.setUri(uri);
            return this;
        }

        @Override
        public HttpMethod getMethod() {
            return this.request.method();
        }

        @Override
        public HttpMethod method() {
            return request.method();
        }

        @Override
        public String getUri() {
            return this.request.uri();
        }

        @Override
        public String uri() {
            return request.uri();
        }

        @Override
        public HttpVersion getProtocolVersion() {
            return this.request.protocolVersion();
        }

        @Override
        public HttpVersion protocolVersion() {
            return request.protocolVersion();
        }

        @Override
        public HttpRequest setProtocolVersion(HttpVersion version) {
            this.request.setProtocolVersion(version);
            return this;
        }

        @Override
        public HttpHeaders headers() {
            return this.request.headers();
        }

        @Override
        public DecoderResult getDecoderResult() {
            return this.request.decoderResult();
        }

        @Override
        public DecoderResult decoderResult() {
            return request.decoderResult();
        }

        @Override
        public void setDecoderResult(DecoderResult result) {
            this.request.setDecoderResult(result);
        }

        @Override
        public String toString() {
            return this.getClass().getName() + "(" + this.request.toString() + ")";
        }
    }

    /**
     * Decorator around {@link StreamedHttpRequest} to adapt a publisher of {@link ByteBuffer} (i.e. {@link
     * software.amazon.awssdk.http.async.SdkHttpContentPublisher}) to a publisher of {@link HttpContent}.
     * <p />
     * This publisher also prevents the adapted publisher from publishing more content to the subscriber than
     * the specified 'Content-Length' of the request.
     */
    private static class StreamedRequest extends DelegateHttpRequest implements StreamedHttpRequest {

        private final Publisher<ByteBuffer> publisher;
        private final Channel channel;
        private final Optional<Long> requestContentLength;
        private long written = 0L;
        private boolean done;
        private Subscription subscription;

        StreamedRequest(HttpRequest request, Publisher<ByteBuffer> publisher, Channel channel) {
            super(request);
            this.publisher = publisher;
            this.channel = channel;
            this.requestContentLength = contentLength(request);
        }

        @Override
        public void subscribe(Subscriber<? super HttpContent> subscriber) {
            publisher.subscribe(new Subscriber<ByteBuffer>() {
                @Override
                public void onSubscribe(Subscription subscription) {
                    StreamedRequest.this.subscription = subscription;
                    subscriber.onSubscribe(subscription);
                }

                @Override
                public void onNext(ByteBuffer byteBuffer) {
                    if (done) {
                        return;
                    }

                    int newLimit = clampedBufferLimit(byteBuffer.remaining());
                    byteBuffer.limit(newLimit);
                    ByteBuf buffer = channel.alloc().buffer(byteBuffer.remaining());
                    buffer.writeBytes(byteBuffer);
                    HttpContent content = new DefaultHttpContent(buffer);

                    subscriber.onNext(content);
                    written += newLimit;

                    if (!shouldContinuePublishing()) {
                        done = true;
                        subscription.cancel();
                        subscriber.onComplete();
                    }
                }

                @Override
                public void onError(Throwable t) {
                    if (!done) {
                        done = true;
                        subscriber.onError(t);
                    }
                }

                @Override
                public void onComplete() {
                    if (!done) {
                        done = true;
                        subscriber.onComplete();
                    }
                }
            });
        }

        private int clampedBufferLimit(int bufLen) {
            return requestContentLength.map(cl ->
                (int) Math.min(cl - written, bufLen)
            ).orElse(bufLen);
        }

        private boolean shouldContinuePublishing() {
            return requestContentLength.map(cl -> written < cl).orElse(true);
        }

        private static Optional<Long> contentLength(HttpRequest request) {
            String value = request.headers().get("Content-Length");
            if (value != null) {
                try {
                    return Optional.of(Long.parseLong(value));
                } catch (NumberFormatException e) {
                    log.warn("Unable  to parse 'Content-Length' header. Treating it as non existent.");
                }
            }
            return Optional.empty();
        }
    }
}
