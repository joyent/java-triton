package com.joyent.triton.domain;

import java.io.Serializable;
import java.util.Map;

/**
 * Interface indicating that an object is considered a core domain object.
 *
 * @author <a href="https://github.com/dekobon">Elijah Zupancic</a>
 * @since 1.0.0
 */
public interface Entity extends Serializable {

    /**
     * Converts this object into a {@link Map} that has a key
     * for each bean in the object. This is useful for systems that need
     * to process each bean as part of an ingestion phase.
     * @return Map with each bean as an key/value
     */
    Map<String, Object> asMap();

    /**
     * Converts this object into a {@link Map} that has a key
     * for each bean in the object and stores the value of each bean as
     * a string. This is useful for systems that need to ingest a set of
     * plain-text properties for every bean of an instance class.
     * @return Map with each bean as a string key/value
     */
    Map<String, String> asStringMap();
}
