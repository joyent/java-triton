package com.joyent.triton;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.joyent.triton.domain.Instance;
import com.joyent.triton.exceptions.CloudApiIOException;
import com.joyent.triton.exceptions.InstanceGoneMissingException;
import com.joyent.triton.http.CloudApiConnectionContext;
import com.joyent.triton.http.CloudApiHttpHeaders;
import com.joyent.triton.http.CloudApiResponseHandler;
import com.joyent.triton.http.HttpCollectionResponse;
import com.joyent.triton.http.JsonEntity;
import com.joyent.triton.queryfilters.InstanceFilter;
import com.joyent.triton.queryfilters.InstanceFilterConverter;
import com.joyent.triton.queryfilters.QueryFilterConverter;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.apache.commons.lang3.ObjectUtils.firstNonNull;
import static org.apache.http.HttpStatus.SC_CREATED;
import static org.apache.http.HttpStatus.SC_GONE;
import static org.apache.http.HttpStatus.SC_NO_CONTENT;
import static org.apache.http.HttpStatus.SC_OK;

/**
 * API to interact directly with instances (machines) on Triton.
 *
 * @author <a href="https://github.com/dekobon">Elijah Zupancic</a>
 * @since 1.0.0
 */
public class Instances extends BaseApiAccessor {
    /**
     * Constant indicating that the resource count was unavailable.
     */
    private static final int UNAVAILABLE = -1;

    /**
     * Logger instance.
     */
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * Query filter converter class that allows you to convert from a {@link InstanceFilter}
     * to a {@link java.util.Collection} of {@link NameValuePair}.
     */
    private final QueryFilterConverter<InstanceFilter> instanceFilterConverter =
            new InstanceFilterConverter();

    /**
     * Response handler for HEAD requests.
     */
    private final CloudApiResponseHandler<Map<String, Header>> headerInstanceHandler;

    /**
     * Response handler for instance listing.
     */
    private final CloudApiResponseHandler<List<Instance>> listInstanceHandler;

    /**
     * Response handler for create instance.
     */
    private final CloudApiResponseHandler<Instance> createInstanceHandler;

    /**
     * Response handler for delete instance.
     */
    private final CloudApiResponseHandler<Void> deleteInstanceHandler;

    /**
     * Response handler for finding instances by id.
     */
    private final CloudApiResponseHandler<Instance> findInstanceHandler;

    /**
     * Response handler for updating instance tags.
     */
    private final CloudApiResponseHandler<Map<String, String>> tagsHandler;

    /**
     * Creates a new configured {@code Instances} API instance.
     * @param cloudApi reference to {@link CloudApi} instance that is backing API calls.
     * @param mapper reference to the jackson object mapper to use for processing JSON
     */
    Instances(final CloudApi cloudApi, final ObjectMapper mapper) {
        super(cloudApi, mapper);

        this.headerInstanceHandler = new CloudApiResponseHandler<>(
                "get headers", mapper, new TypeReference<Map<String, Header>>() { }, SC_OK, true
        );
        this.listInstanceHandler = new CloudApiResponseHandler<>(
                "list instances", mapper, new TypeReference<List<Instance>>() { }, SC_OK, false
        );
        this.createInstanceHandler = new CloudApiResponseHandler<>(
                "create instance", mapper, new TypeReference<Instance>() { }, SC_CREATED, false
        );
        this.deleteInstanceHandler = new CloudApiResponseHandler<>(
                "delete instance", mapper, new TypeReference<Void>() { }, SC_NO_CONTENT, false
        );
        this.findInstanceHandler = new CloudApiResponseHandler<>(
                "find instance", mapper, new TypeReference<Instance>() { },
                new int[] {SC_OK, SC_GONE}, true
        );
        this.tagsHandler = new CloudApiResponseHandler<>(
                "tag instance", mapper, new TypeReference<Map<String, String>>() { }, SC_OK, false
        );
    }

    /**
     * Lists all instances.
     *
     * @return Iterator of instance objects
     * @throws IOException thrown when there is a problem with getting the instance list
     */
    public Iterator<Instance> list() throws IOException {
        try (CloudApiConnectionContext context = getCloudApi().createConnectionContext()) {
            return list(context);
        }
    }

    /**
     * Lists all instances.
     *
     * @param context request context used for sharing resources between API operations
     * @return Iterator of instance objects
     * @throws IOException thrown when there is a problem with getting the instance list
     */
    public Iterator<Instance> list(final CloudApiConnectionContext context) throws IOException {
        return list(context, new InstanceFilter());
    }

    /**
     * Lists instances that match the filter criteria.
     *
     * @param context request context used for sharing resources between API operations
     * @param filter query filter to filter results by
     * @return Iterator of instance objects
     * @throws IOException thrown when there is a problem with getting the instance list
     */
    public Iterator<Instance> list(final CloudApiConnectionContext context,
                                   final InstanceFilter filter) throws IOException {
        Objects.requireNonNull(context, "Context object must be present");
        Objects.requireNonNull(context, "Filter object must be present");

        final List<NameValuePair> filterParams = instanceFilterConverter.urlParamsFromFilter(filter);
        final String path = String.format("/%s/machines", getConfig().getUser());

        final HttpClient client = context.getHttpClient();

        final HttpHead head = getConnectionFactory().head(path, filterParams);

        /* We first perform a head request because we can use it to determine
         * if any results are going to be returned. If there are no results,
         * we can just return an empty collection and give up. */
        final Map<String, Header> headHeaders = execute(
                context, head, headerInstanceHandler);
        final int headResourceCount = resourceCount(headHeaders);

        // -1 indicates error, 1+ indicates values present
        if (headResourceCount == 0) {
            return Collections.emptyIterator();
        }

        final HttpGet get = getConnectionFactory().get(path, filterParams);

        @SuppressWarnings("unchecked")
        final HttpCollectionResponse<Instance> result =
                (HttpCollectionResponse<Instance>) execute(context,
                        get, listInstanceHandler);

        final HttpResponse response = result.getResponse();

        final String resourceCountVal = response.getFirstHeader(
                CloudApiHttpHeaders.X_RESOURCE_COUNT).getValue();
        @SuppressWarnings("ConstantConditions")
        final int resourceCount = Integer.parseInt(firstNonNull(resourceCountVal, "0"));
        final String queryLimitVal = response.getFirstHeader(
                CloudApiHttpHeaders.X_QUERY_LIMIT).getValue();
        @SuppressWarnings("ConstantConditions")
        final int queryLimit = Integer.parseInt(firstNonNull(queryLimitVal, "1000"));

        if (resourceCount < queryLimit) {
            logger.info("Total instances: {}", result.size());
            return result.getWrapped().iterator();
        }

        return result.iterator();
    }

    /**
     * Create a new instance.
     *
     * @param context request context used for sharing resources between API operations
     * @param instance instance object with the appropriate fields populated
     * @return instance object with properties updated based on the results of the create operation
     * @throws IOException thrown when there is a problem with creating a new instance
     */
    public Instance create(final CloudApiConnectionContext context,
                           final Instance instance) throws IOException {
        Objects.requireNonNull(context, "Context object must be present");
        Objects.requireNonNull(instance, "Instance must be present");
        Objects.requireNonNull(instance.getPackageId(), "Package id must be present");
        Objects.requireNonNull(instance.getImage(), "Image id must be present");

        final String path = String.format("/%s/machines", getConfig().getUser());
        final HttpPost post = getConnectionFactory().post(path);

        HttpEntity entity = new JsonEntity(getMapper(), instance);
        post.setEntity(entity);

        final Instance result = execute(context, post,
                createInstanceHandler);

        logger.info("Created new instance: {}", result.getId());

        return result;
    }

    /**
     * Delete an instance.
     *
     * @param instance instance to delete
     * @throws IOException thrown when there is a problem deleting instance
     */
    public void delete(final Instance instance) throws IOException {
        Objects.requireNonNull(instance, "Instance must be present");
        delete(instance.getId());
    }

    /**
     * Delete an instance.
     *
     * @param instanceId id of instance to delete
     * @throws IOException thrown when there is a problem deleting instance
     */
    public void delete(final UUID instanceId) throws IOException {
        try (CloudApiConnectionContext context = getCloudApi().createConnectionContext()) {
            delete(context, instanceId);
        }
    }

    /**
     * Delete an instance.
     *
     * @param context request context used for sharing resources between API operations
     * @param instance instance to delete
     * @throws IOException thrown when there is a problem deleting instance
     */
    public void delete(final CloudApiConnectionContext context,
                       final Instance instance) throws IOException {
        Objects.requireNonNull(instance, "Instance must be present");
        delete(context, instance.getId());
    }

    /**
     * Delete an instance.
     *
     * @param context request context used for sharing resources between API operations
     * @param instanceId id of instance to delete
     * @throws IOException thrown when there is a problem deleting instance
     */
    public void delete(final CloudApiConnectionContext context,
                       final UUID instanceId) throws IOException {
        Objects.requireNonNull(context, "Context object must be present");
        Objects.requireNonNull(instanceId, "Instance id to be deleted must be present");

        final String path = String.format("/%s/machines/%s",
                getConfig().getUser(), instanceId);
        final HttpDelete delete = getConnectionFactory().delete(path);

        execute(context, delete, deleteInstanceHandler);

        logger.info("Deleted instance: {}", instanceId);
    }

    /**
     * Wait for the specified instance's state to change from an expected value.
     * If the state expected is not available initially, then we consider it a
     * state change.
     *
     * @param instance instance to monitor
     * @param initialState expected initial state
     * @param maxWaitTimeMs maximum amount of time to wait for a state change in milliseconds
     * @param waitIntervalMs time to wait between state change checks in milliseconds
     * @return a reference to the instance that changed
     * @throws IOException thrown when something goes wrong when checking state
     */
    public Instance waitForStateChange(final Instance instance,
                                       final String initialState,
                                       final long maxWaitTimeMs,
                                       final long waitIntervalMs) throws IOException {
        Objects.requireNonNull(instance, "Instance must be present");

        if (instance.getId() == null) {
            String msg = String.format("Instance doesn't contain a valid id. Instance: %s", instance);
            throw new IllegalArgumentException(msg);
        }

        return waitForStateChange(instance.getId(), initialState, maxWaitTimeMs, waitIntervalMs);
    }

    /**
     * Wait for the specified instance's state to change from an expected value.
     * If the state expected is not available initially, then we consider it a
     * state change.
     *
     * @param instanceId id of instance to monitor
     * @param initialState expected initial state
     * @param maxWaitTimeMs maximum amount of time to wait for a state change in milliseconds
     * @param waitIntervalMs time to wait between state change checks in milliseconds
     * @return a reference to the instance that changed
     * @throws IOException thrown when something goes wrong when checking state
     */
    public Instance waitForStateChange(final UUID instanceId,
                                       final String initialState,
                                       final long maxWaitTimeMs,
                                       final long waitIntervalMs) throws IOException {
        try (CloudApiConnectionContext context = getCloudApi().createConnectionContext()) {
            return waitForStateChange(context, instanceId, initialState, maxWaitTimeMs, waitIntervalMs);
        }
    }

    /**
     * Wait for the specified instance's state to change from an expected value.
     * If the state expected is not available initially, then we consider it a
     * state change.
     *
     * @param context request context used for sharing resources between API operations
     * @param instance instance to monitor
     * @param initialState expected initial state
     * @param maxWaitTimeMs maximum amount of time to wait for a state change in milliseconds
     * @param waitIntervalMs time to wait between state change checks in milliseconds
     * @return a reference to the instance that changed
     * @throws IOException thrown when something goes wrong when checking state
     */
    public Instance waitForStateChange(final CloudApiConnectionContext context,
                                       final Instance instance,
                                       final String initialState,
                                       final long maxWaitTimeMs,
                                       final long waitIntervalMs) throws IOException {
        Objects.requireNonNull(instance, "Instance must be present");

        if (instance.getId() == null) {
            String msg = String.format("Instance doesn't contain a valid id. Instance: %s", instance);
            throw new IllegalArgumentException(msg);
        }

        return waitForStateChange(context, instance.getId(), initialState, maxWaitTimeMs, waitIntervalMs);
    }

    /**
     * Wait for the specified instance's state to change from an expected value.
     * If the state expected is not available initially, then we consider it a
     * state change.
     *
     * @param context request context used for sharing resources between API operations
     * @param instanceId id of instance to monitor
     * @param initialState expected initial state
     * @param maxWaitTimeMs maximum amount of time to wait for a state change in milliseconds
     * @param waitIntervalMs time to wait between state change checks in milliseconds
     * @return a reference to the instance that changed
     * @throws IOException thrown when something goes wrong when checking state
     */
    public Instance waitForStateChange(final CloudApiConnectionContext context,
                                       final UUID instanceId,
                                       final String initialState,
                                       final long maxWaitTimeMs,
                                       final long waitIntervalMs) throws IOException {
        Objects.requireNonNull(instanceId, "Instance id must be present");
        Objects.requireNonNull(initialState, "Initial state value must be present");

        if (maxWaitTimeMs < 0) {
            throw new IllegalArgumentException("Maximum wait time must be 0 milliseconds "
                    + "or greater");
        }

        Instance lastPoll = findById(context, instanceId);

        if (lastPoll == null) {
            return null;
        }

        // Don't even bother sleeping if the state was never set
        if (!lastPoll.getState().equals(initialState)) {
            logger.debug("State changed from [{}] to [{}]- no longer waiting",
                    initialState,  lastPoll.getState());
            return lastPoll;
        }

        long waited = 0;

        while (true) {
            try {
                if (waitIntervalMs > 0) {
                    Thread.sleep(waitIntervalMs);
                    waited += waitIntervalMs;
                }
            } catch (InterruptedException e) {
                return null;
            }

            lastPoll = findById(context, instanceId);

            if (lastPoll == null) {
                final String msg = String.format("The instance [%s] was successfully polled "
                                + "previously, but is no longer available. Maybe it was deleted?",
                        instanceId);
                throw new InstanceGoneMissingException(msg);
            }

            // Sanity check to make sure that we are monitoring the correct instance
            if (!lastPoll.getId().equals(instanceId)) {
                final CloudApiIOException e = new CloudApiIOException("Wrong instance id returned");
                e.setContextValue("expectedId", instanceId);
                e.setContextValue("actualId", lastPoll.getId());

                throw e;
            }

            if (!lastPoll.getState().equals(initialState)) {
                logger.debug("State changed from [{}] to [{}] - no longer waiting",
                        initialState,  lastPoll.getState());
                break;
            }

            if (waited > maxWaitTimeMs) {
                logger.debug("Exceeded maximum wait time [{} ms] for state change - "
                        + "no longer waiting", maxWaitTimeMs);
                break;
            }
        }

        return lastPoll;
    }

    /**
     * Finds an instance by its id.
     *
     * @param instanceId id of instance to find
     * @return instance if found, otherwise null
     * @throws IOException thrown when we have a problem finding an instance by id
     */
    public Instance findById(final UUID instanceId) throws IOException {
        try (CloudApiConnectionContext context = getCloudApi().createConnectionContext()) {
            return findById(context, instanceId);
        }
    }

    /**
     * Finds an instance by its id.
     *
     * @param context request context used for sharing resources between API operations
     * @param instanceId id of instance to find
     * @return instance if found, otherwise null
     * @throws IOException thrown when we have a problem finding an instance by id
     */
    public Instance findById(final CloudApiConnectionContext context,
                             final UUID instanceId) throws IOException {
        final String path = String.format("/%s/machines/%s",
                getConfig().getUser(), instanceId);
        final HttpGet get = getConnectionFactory().get(path);

        return execute(context, get, findInstanceHandler);
    }

    /**
     * Add additional tags to an instance.
     *
     * @param instanceId instance id to add tags to
     * @param tags map of tags to add to instance
     * @return tags map of added tags and existing tags as sent by server response
     * @throws IOException thrown when we can't add tags to an instance
     */
    public Map<String, String> addTags(final UUID instanceId,
                                       final Map<String, String> tags) throws IOException {
        try (CloudApiConnectionContext context = getCloudApi().createConnectionContext()) {
            return addTags(context, instanceId, tags);
        }
    }

    /**
     * Add additional tags to an instance.
     *
     * @param context request context used for sharing resources between API operations
     * @param instanceId instance id to add tags to
     * @param tags map of tags to add to instance
     * @return tags map of added tags and existing tags as sent by server response
     * @throws IOException thrown when we can't add tags to an instance
     */
    public Map<String, String> addTags(final CloudApiConnectionContext context,
                                       final UUID instanceId,
                                       final Map<String, String> tags) throws IOException {
        Objects.requireNonNull(context, "Context object must be present");
        Objects.requireNonNull(instanceId, "Instance id must be present");
        Objects.requireNonNull(tags, "Tags to add must be present");

        if (tags.isEmpty()) {
            return Collections.emptyMap();
        }

        final String path = String.format("/%s/machines/%s/tags", getConfig().getUser(), instanceId);
        final HttpPost post = getConnectionFactory().post(path);
        final HttpEntity entity = new JsonEntity(getMapper(), tags);
        post.setEntity(entity);

        final Map<String, String> result = execute(context, post, tagsHandler);

        if (logger.isInfoEnabled()) {
            logger.info("Add/updated [%d] tags to instance [{}]", result.size());
        }

        return result;
    }

    /**
     * Replace all of the tags in an instance.
     *
     * @param instanceId instance id to replace tags
     * @param tags map of tags to replace
     * @return tags map of resultant tags as sent by server response
     * @throws IOException thrown when we can't replace an instance's tags
     */
    public Map<String, String> replaceTags(final UUID instanceId,
                                           final Map<String, String> tags) throws IOException {
        try (CloudApiConnectionContext context = getCloudApi().createConnectionContext()) {
            return replaceTags(context, instanceId, tags);
        }
    }

    /**
     * Replace all of the tags in an instance.
     *
     * @param context request context used for sharing resources between API operations
     * @param instanceId instance id to replace tags
     * @param tags map of tags to replace
     * @return tags map of resultant tags as sent by server response
     * @throws IOException thrown when we can't replace an instance's tags
     */
    public Map<String, String> replaceTags(final CloudApiConnectionContext context,
                                           final UUID instanceId,
                                           final Map<String, String> tags) throws IOException {
        Objects.requireNonNull(context, "Context object must be present");
        Objects.requireNonNull(instanceId, "Instance id must be present");
        Objects.requireNonNull(tags, "Tags to replace must be present");

        final String path = String.format("/%s/machines/%s/tags", getConfig().getUser(), instanceId);
        final HttpPut put = getConnectionFactory().put(path);

        final HttpEntity entity = new JsonEntity(getMapper(), tags);
        put.setEntity(entity);

        final Map<String, String> result = execute(context, put,
                tagsHandler);

        if (logger.isInfoEnabled()) {
            logger.info("Replaced all tags on instance [{}] with [%d] tags", result.size());
        }

        return result;
    }

    /**
     * Calculates the amount of results from an operation that contained the resource count
     * HTTP response header.
     *
     * @param headers response headers to process
     * @return total number of resources or -1 if unavailable
     */
    private int resourceCount(final Map<String, Header> headers) {
        if (headers == null) {
            return UNAVAILABLE;
        }

        final Header header = headers.get(CloudApiHttpHeaders.X_RESOURCE_COUNT);

        if (header != null) {
            final String headResourceCountVal = header.getValue();
            @SuppressWarnings("ConstantConditions")
            // We default to -1 in order to indicate an error
            final int headResourceCount = Integer.parseInt(
                    firstNonNull(headResourceCountVal, String.valueOf(UNAVAILABLE)));
            return headResourceCount;
        }

        return UNAVAILABLE;
    }
}
