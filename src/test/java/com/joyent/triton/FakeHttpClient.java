package com.joyent.triton;

import com.joyent.triton.http.CloudApiConnectionContext;
import org.apache.http.HttpHost;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.ResponseHandler;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.protocol.HttpContext;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.Queue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fake implementation of {@link HttpClient} that always returns the same response
 * no matter what is executed.
 */
@SuppressWarnings("deprecation")
public class FakeHttpClient implements HttpClient {
    private final Queue<HttpResponse> responses;

    public FakeHttpClient(final HttpResponse response) {
        this.responses = new LinkedList<>(Collections.singletonList(response));
    }

    public FakeHttpClient(final Queue<HttpResponse> responses) {
        this.responses = responses;
    }

    @Override
    public org.apache.http.params.HttpParams getParams() {
        throw new UnsupportedOperationException("We don't test deprecated methods");
    }

    @Override
    public org.apache.http.conn.ClientConnectionManager getConnectionManager() {
        throw new UnsupportedOperationException("We don't test deprecated methods");
    }

    @Override
    public HttpResponse execute(HttpUriRequest request) throws IOException, ClientProtocolException {
        return responses.poll();
    }

    @Override
    public HttpResponse execute(HttpUriRequest request, HttpContext context) throws IOException, ClientProtocolException {
        return responses.poll();
    }

    @Override
    public HttpResponse execute(HttpHost target, HttpRequest request) throws IOException, ClientProtocolException {
        return responses.poll();
    }

    @Override
    public HttpResponse execute(HttpHost target, HttpRequest request, HttpContext context) throws IOException, ClientProtocolException {
        return responses.poll();
    }

    @Override
    public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> responseHandler) throws IOException, ClientProtocolException {
        return responseHandler.handleResponse(responses.poll());
    }

    @Override
    public <T> T execute(HttpUriRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context) throws IOException, ClientProtocolException {
        return responseHandler.handleResponse(responses.poll());
    }

    @Override
    public <T> T execute(HttpHost target, HttpRequest request, ResponseHandler<? extends T> responseHandler) throws IOException, ClientProtocolException {
        return responseHandler.handleResponse(responses.poll());
    }

    @Override
    public <T> T execute(HttpHost target, HttpRequest request, ResponseHandler<? extends T> responseHandler, HttpContext context) throws IOException, ClientProtocolException {
        return responseHandler.handleResponse(responses.poll());
    }

    public static CloudApiConnectionContext createMockContext(final HttpResponse response) {
        return createMockContext(new LinkedList<>(Collections.singletonList(response)));
    }

    public static CloudApiConnectionContext createMockContext(final Queue<HttpResponse> responses) {
        final CloudApiConnectionContext context = mock(CloudApiConnectionContext.class);
        final HttpClientContext httpClientContext = new HttpClientContext();
        when(context.getHttpContext()).thenReturn(httpClientContext);

        final HttpClient client = new FakeHttpClient(responses);
        when(context.getHttpClient()).thenReturn(client);

        return context;
    }
}
