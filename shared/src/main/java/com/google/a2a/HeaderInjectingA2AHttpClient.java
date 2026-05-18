package com.google.a2a;

import io.a2a.client.http.A2AHttpClient;
import io.a2a.client.http.A2AHttpResponse;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public class HeaderInjectingA2AHttpClient implements A2AHttpClient {
    private final A2AHttpClient delegate;
    private final Map<String, String> extraHeaders;

    public HeaderInjectingA2AHttpClient(A2AHttpClient delegate, Map<String, String> extraHeaders) {
        this.delegate = delegate;
        this.extraHeaders = extraHeaders;
    }

    @Override
    public GetBuilder createGet() {
        return new WrappedGetBuilder(delegate.createGet());
    }

    @Override
    public PostBuilder createPost() {
        return new WrappedPostBuilder(delegate.createPost());
    }

    @Override
    public DeleteBuilder createDelete() {
        return new WrappedDeleteBuilder(delegate.createDelete());
    }

    private class WrappedGetBuilder implements GetBuilder {
        private final GetBuilder delegateBuilder;
        WrappedGetBuilder(GetBuilder delegateBuilder) {
            this.delegateBuilder = delegateBuilder.addHeaders(extraHeaders);
        }
        @Override public GetBuilder url(String url) { return delegateBuilder.url(url); }
        @Override public GetBuilder addHeaders(Map<String, String> headers) { return delegateBuilder.addHeaders(headers); }
        @Override public GetBuilder addHeader(String key, String value) { return delegateBuilder.addHeader(key, value); }
        @Override public A2AHttpResponse get() throws IOException, InterruptedException { return delegateBuilder.get(); }
        @Override public CompletableFuture<Void> getAsyncSSE(Consumer<String> onLine, Consumer<Throwable> onError, Runnable onComplete) throws IOException, InterruptedException {
            return delegateBuilder.getAsyncSSE(onLine, onError, onComplete);
        }
    }

    private class WrappedPostBuilder implements PostBuilder {
        private final PostBuilder delegateBuilder;
        WrappedPostBuilder(PostBuilder delegateBuilder) {
            this.delegateBuilder = delegateBuilder.addHeaders(extraHeaders);
        }
        @Override public PostBuilder url(String url) { return delegateBuilder.url(url); }
        @Override public PostBuilder addHeaders(Map<String, String> headers) { return delegateBuilder.addHeaders(headers); }
        @Override public PostBuilder addHeader(String key, String value) { return delegateBuilder.addHeader(key, value); }
        @Override public PostBuilder body(String body) { return delegateBuilder.body(body); }
        @Override public A2AHttpResponse post() throws IOException, InterruptedException { return delegateBuilder.post(); }
        @Override public CompletableFuture<Void> postAsyncSSE(Consumer<String> onLine, Consumer<Throwable> onError, Runnable onComplete) throws IOException, InterruptedException {
            return delegateBuilder.postAsyncSSE(onLine, onError, onComplete);
        }
    }

    private class WrappedDeleteBuilder implements DeleteBuilder {
        private final DeleteBuilder delegateBuilder;
        WrappedDeleteBuilder(DeleteBuilder delegateBuilder) {
            this.delegateBuilder = delegateBuilder.addHeaders(extraHeaders);
        }
        @Override public DeleteBuilder url(String url) { return delegateBuilder.url(url); }
        @Override public DeleteBuilder addHeaders(Map<String, String> headers) { return delegateBuilder.addHeaders(headers); }
        @Override public DeleteBuilder addHeader(String key, String value) { return delegateBuilder.addHeader(key, value); }
        @Override public A2AHttpResponse delete() throws IOException, InterruptedException { return delegateBuilder.delete(); }
    }
}
