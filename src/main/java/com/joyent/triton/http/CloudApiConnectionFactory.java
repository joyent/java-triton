package com.joyent.triton.http;

import com.joyent.http.signature.Signer;
import com.joyent.http.signature.ThreadLocalSigner;
import com.joyent.http.signature.apache.httpclient.HttpSignatureConfigurator;
import com.joyent.triton.config.ConfigContext;
import com.joyent.triton.config.ConfigurationException;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpHost;
import org.apache.http.NameValuePair;
import org.apache.http.auth.Credentials;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.entity.ContentType;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.message.BasicHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.KeyPair;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import static org.apache.commons.lang3.BooleanUtils.toBoolean;

/**
 * Factory class that creates instances of
 * {@link org.apache.http.client.HttpClient} configured for use with
 * HTTP signature based authentication.
 *
 * @author <a href="https://github.com/dekobon">Elijah Zupancic</a>
 * @since 1.0.0
 */
public class CloudApiConnectionFactory {
    /**
     * Logger instance.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Default DNS resolver for all connections to the CloudAPI.
     */
    private static final DnsResolver DNS_RESOLVER = new ShufflingDnsResolver();

    /**
     * Default HTTP headers to send to all requests to the CloudAPI.
     */
    private static final Collection<? extends Header> HEADERS = Collections.singletonList(
            new BasicHeader(HttpHeaders.ACCEPT, ContentType.APPLICATION_JSON.getMimeType())
    );

    /**
     * Configuration context that provides connection details.
     */
    private final ConfigContext config;

    /**
     * HTTP Signatures authentication configuration helper.
     */
    private final HttpSignatureConfigurator signatureConfigurator;

    /**
     * Apache HTTP Client connection builder helper.
     */
    private final HttpClientBuilder httpClientBuilder;

    /**
     * Create new instance using the passed configuration.
     * @param config configuration of the connection parameters
     */
    public CloudApiConnectionFactory(final ConfigContext config) {
        Objects.requireNonNull(config, "Configuration context must be present");

        this.config = config;

        if (config.getCloudAPIURL() == null) {
            throw new ConfigurationException("The CloudAPI URL setting must be set");
        }

        // Test root URL for validity

        try {
            new URI(config.getCloudAPIURL());
        } catch (URISyntaxException e) {
            final String msg = String.format("Invalid CloudAPI URL: %s",
                    config.getCloudAPIURL());
            throw new ConfigurationException(msg, e);
        }

        // Setup configurator helper

        final boolean useNativeCodeToSign;

        if (config.disableNativeSignatures() == null) {
            useNativeCodeToSign = true;
        } else {
            useNativeCodeToSign = !config.disableNativeSignatures();
        }

        if (config.noAuth()) {
            this.signatureConfigurator = null;
        } else {
            this.signatureConfigurator = new HttpSignatureConfigurator(
                    createKeyPair(), createCredentials(), useNativeCodeToSign);
        }

        this.httpClientBuilder = createBuilder();
    }

    /**
     * Configures the builder class with all of the settings needed to connect to
     * the CloudAPI.
     *
     * @return configured instance
     */
    protected HttpClientBuilder createBuilder() {
        final boolean noAuth = ObjectUtils.firstNonNull(config.noAuth(), false);

        final RequestConfig requestConfig = RequestConfig.custom()
                .setAuthenticationEnabled(!noAuth)
                .setContentCompressionEnabled(true)
                .build();

        final HttpClientBuilder builder = HttpClients.custom()
                .setDefaultHeaders(HEADERS)
                .setDefaultRequestConfig(requestConfig)
                .setRetryHandler(new CloudApiHttpRequestRetryHandler(config));

        if (!noAuth) {
            signatureConfigurator.configure(builder);
        }

        final HttpHost proxyHost = findProxyServer();

        if (proxyHost != null) {
            builder.setProxy(proxyHost);
        }

        builder.addInterceptorFirst(new RequestIdInterceptor());

        return builder;
    }

    /**
     * Creates a {@link Credentials} instance based on the stored
     * {@link ConfigContext}.
     *
     * @return credentials for the CloudAPI
     */
    protected Credentials createCredentials() {
        final String user = config.getUser();
        Objects.requireNonNull(user, "User must be present");

        final String keyId = config.getKeyId();
        Objects.requireNonNull(keyId, "Key id must be present");

        return new UsernamePasswordCredentials(user, keyId);
    }

    /**
     * Creates a {@link KeyPair} object based on the factory's configuration.
     * @return an encryption key pair
     */
    protected KeyPair createKeyPair() {
        final KeyPair keyPair;
        final String password = config.getPassword();
        final String keyPath = config.getKeyPath();
        final ThreadLocalSigner threadLocalSigner;

        if (config.disableNativeSignatures() == null) {
            threadLocalSigner = new ThreadLocalSigner();
        } else {
            threadLocalSigner = new ThreadLocalSigner(!config.disableNativeSignatures());
        }

        final Signer signer = threadLocalSigner.get();

        if (logger.isDebugEnabled()) {
            final boolean nativeGmp = toBoolean(System.getProperty("native.jnagmp"));
            final String enabled = BooleanUtils.toString(nativeGmp, "enabled", "disabled");
            logger.debug("Native GMP is {}", enabled);
        }

        if (signer == null) {
            final String msg = "Error getting signer instance from thread local";
            throw new NullPointerException(msg);
        }

        try {
            if (keyPath != null) {
                keyPair = signer.getKeyPair(new File(keyPath).toPath());
            } else {
                final char[] charPassword;

                if (password != null) {
                    charPassword = password.toCharArray();
                } else {
                    charPassword = null;
                }

                final String privateKeyContent = config.getPrivateKeyContent();

                if (privateKeyContent == null) {
                    String msg = "Private key content setting must be set if "
                            + "key file path is not set";
                    ConfigurationException exception = new ConfigurationException(msg);
                    exception.setContextValue("config", config);
                    throw exception;
                }

                keyPair = signer.getKeyPair(privateKeyContent, charPassword);
            }
        } catch (IOException e) {
            String msg = String.format("Unable to read key files from path: %s",
                    keyPath);
            throw new ConfigurationException(msg, e);
        }

        return keyPair;
    }

    /**
     * Derives the CloudAPI URI for a given path.
     *
     * @param path full path
     * @return full URI as string of resource
     */
    protected String uriForPath(final String path) {
        Objects.requireNonNull(path, "Path must be present");

        if (path.startsWith("/")) {
            return String.format("%s%s", config.getCloudAPIURL(), path);
        } else {
            return String.format("%s/%s", config.getCloudAPIURL(), path);
        }
    }

    /**
     * Derives the CloudAPI URI for a given path with the passed query
     * parameters.
     *
     * @param path full path
     * @param params query parameters to add to the URI
     * @return full URI as string of resource
     */
    protected String uriForPath(final String path, final List<NameValuePair> params) {
        Objects.requireNonNull(path, "Path must be present");
        Objects.requireNonNull(params, "Params must be present");

        try {
            final URIBuilder uriBuilder = new URIBuilder(uriForPath(path));
            uriBuilder.addParameters(params);
            return uriBuilder.build().toString();
        } catch (URISyntaxException e) {
            throw new ConfigurationException(String.format("Invalid path in URI: %s", path));
        }
    }

    /**
     * Finds the host of the proxy server that was configured as part of the
     * JVM settings.
     *
     * @return proxy server as {@link HttpHost}, if no proxy then null
     */
    protected HttpHost findProxyServer() {
        final ProxySelector proxySelector = ProxySelector.getDefault();
        List<Proxy> proxies = proxySelector.select(URI.create(config.getCloudAPIURL()));

        if (!proxies.isEmpty()) {
            /* The Apache HTTP Client doesn't understand the concept of multiple
             * proxies, so we use only the first one returned. */
            final Proxy proxy = proxies.get(0);

            switch (proxy.type()) {
                case DIRECT:
                    return null;
                case SOCKS:
                    throw new ConfigurationException("SOCKS proxies are unsupported");
                default:
                    // do nothing and fall through
            }

            if (proxy.address() instanceof InetSocketAddress) {
                InetSocketAddress sa = (InetSocketAddress) proxy.address();

                return new HttpHost(sa.getHostName(), sa.getPort());
            } else {
                String msg = String.format(
                        "Expecting proxy to be instance of InetSocketAddress. "
                        + " Actually: %s", proxy.address());
                throw new ConfigurationException(msg);
            }
        } else {
            return null;
        }
    }

    /**
     * Creates a new configured instance of {@link CloseableHttpClient} based
     * on the factory's configuration.
     *
     * @return new connection object instance
     */
    public CloseableHttpClient createConnection() {
        final ConnectionSocketFactory socketFactory =
                new CloudApiSSLConnectionSocketFactory(config);

        final RegistryBuilder<ConnectionSocketFactory> registryBuilder =
                RegistryBuilder.create();

        final Registry<ConnectionSocketFactory> socketFactoryRegistry = registryBuilder
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", socketFactory)
                .build();

        final PoolingHttpClientConnectionManager connectionManager =
                new PoolingHttpClientConnectionManager(socketFactoryRegistry,
                        DNS_RESOLVER);

        httpClientBuilder.setConnectionManager(connectionManager);

        return httpClientBuilder.build();
    }

    /**
     * Convenience method used for building DELETE operations.
     * @param path path to resource
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpDelete delete(final String path) {
        return new HttpDelete(uriForPath(path));
    }

    /**
     * Convenience method used for building DELETE operations.
     * @param path path to resource
     * @param params list of query parameters to use in operation
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpDelete delete(final String path, final List<NameValuePair> params) {
        return new HttpDelete(uriForPath(path, params));
    }

    /**
     * Convenience method used for building GET operations.
     * @param path path to resource
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpGet get(final String path) {
        return new HttpGet(uriForPath(path));
    }

    /**
     * Convenience method used for building GET operations.
     * @param path path to resource
     * @param params list of query parameters to use in operation
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpGet get(final String path, final List<NameValuePair> params) {
        return new HttpGet(uriForPath(path, params));
    }

    /**
     * Convenience method used for building HEAD operations.
     * @param path path to resource
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpHead head(final String path) {
        return new HttpHead(uriForPath(path));
    }

    /**
     * Convenience method used for building HEAD operations.
     * @param path path to resource
     * @param params list of query parameters to use in operation
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpHead head(final String path, final List<NameValuePair> params) {
        return new HttpHead(uriForPath(path, params));
    }

    /**
     * Convenience method used for building POST operations.
     * @param path path to resource
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpPost post(final String path) {
        return new HttpPost(uriForPath(path));
    }

    /**
     * Convenience method used for building POST operations.
     * @param path path to resource
     * @param params list of query parameters to use in operation
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpPost post(final String path, final List<NameValuePair> params) {
        return new HttpPost(uriForPath(path, params));
    }

    /**
     * Convenience method used for building PUT operations.
     * @param path path to resource
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpPut put(final String path) {
        return new HttpPut(uriForPath(path));
    }

    /**
     * Convenience method used for building PUT operations.
     * @param path path to resource
     * @param params list of query parameters to use in operation
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpPut put(final String path, final List<NameValuePair> params) {
        return new HttpPut(uriForPath(path, params));
    }

    HttpSignatureConfigurator getSignatureConfigurator() {
        return signatureConfigurator;
    }
}
