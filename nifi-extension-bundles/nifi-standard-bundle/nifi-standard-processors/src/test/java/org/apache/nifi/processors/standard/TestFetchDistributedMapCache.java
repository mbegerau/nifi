/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard;

import org.apache.nifi.controller.AbstractControllerService;
import org.apache.nifi.distributed.cache.client.Deserializer;
import org.apache.nifi.distributed.cache.client.DistributedMapCacheClient;
import org.apache.nifi.distributed.cache.client.Serializer;
import org.apache.nifi.reporting.InitializationException;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class TestFetchDistributedMapCache {

    private TestRunner runner;
    private MockCacheClient service;

    @BeforeEach
    public void setup() throws InitializationException {
        runner = TestRunners.newTestRunner(FetchDistributedMapCache.class);
        service = new MockCacheClient();
        runner.addControllerService("service", service);
        runner.enableControllerService(service);
        runner.setProperty(FetchDistributedMapCache.DISTRIBUTED_CACHE_SERVICE, "service");
    }

    @Test
    public void testNoCacheKey() {

        runner.setProperty(FetchDistributedMapCache.CACHE_ENTRY_IDENTIFIER, "${cacheKeyAttribute}");

        final Map<String, String> props = new HashMap<>();
        props.put("cacheKeyAttribute", "1");
        runner.enqueue(new byte[] {}, props);

        runner.run();

        // no cache key attribute
        runner.assertAllFlowFilesTransferred(FetchDistributedMapCache.REL_NOT_FOUND, 1);
        runner.assertTransferCount(FetchDistributedMapCache.REL_NOT_FOUND, 1);
        runner.clearTransferState();
    }

    @Test
    public void testNoCacheKeyValue() {
        runner.setProperty(FetchDistributedMapCache.CACHE_ENTRY_IDENTIFIER, "${cacheKeyAttribute}");
        runner.enqueue(new byte[] {});
        runner.run();

        // Cache key attribute evaluated to empty
        runner.assertAllFlowFilesTransferred(FetchDistributedMapCache.REL_FAILURE, 1);
        runner.assertTransferCount(FetchDistributedMapCache.REL_FAILURE, 1);
        runner.clearTransferState();
    }

    @Test
    public void testFailingCacheService() {
        service.setFailOnCalls(true);
        runner.setProperty(FetchDistributedMapCache.CACHE_ENTRY_IDENTIFIER, "${cacheKeyAttribute}");

        final Map<String, String> props = new HashMap<>();
        props.put("cacheKeyAttribute", "2");
        runner.enqueue(new byte[] {}, props);
        runner.run();

        //Expect the processor to receive an IO exception from the cache service and route to failure
        runner.assertAllFlowFilesTransferred(FetchDistributedMapCache.REL_FAILURE, 1);
        runner.assertTransferCount(FetchDistributedMapCache.REL_FAILURE, 1);

        service.setFailOnCalls(false);
    }

    @Test
    public void testSingleFlowFile() throws IOException {
        service.put("key", "value", new FetchDistributedMapCache.StringSerializer(), new FetchDistributedMapCache.StringSerializer());
        runner.setProperty(FetchDistributedMapCache.CACHE_ENTRY_IDENTIFIER, "${cacheKeyAttribute}");

        final Map<String, String> props = new HashMap<>();
        props.put("cacheKeyAttribute", "key");
        String flowFileContent = "content";
        runner.enqueue(flowFileContent.getBytes(StandardCharsets.UTF_8), props);

        runner.run();

        runner.assertAllFlowFilesTransferred(FetchDistributedMapCache.REL_SUCCESS, 1);
        runner.assertTransferCount(FetchDistributedMapCache.REL_SUCCESS, 1);

        final MockFlowFile outputFlowFile = runner.getFlowFilesForRelationship(FetchDistributedMapCache.REL_SUCCESS).get(0);
        outputFlowFile.assertContentEquals("value");
        runner.clearTransferState();

    }

    @Test
    public void testSingleFlowFileToAttribute() throws IOException {
        service.put("key", "value", new FetchDistributedMapCache.StringSerializer(), new FetchDistributedMapCache.StringSerializer());
        runner.setProperty(FetchDistributedMapCache.CACHE_ENTRY_IDENTIFIER, "${cacheKeyAttribute}");
        runner.setProperty(FetchDistributedMapCache.PUT_CACHE_VALUE_IN_ATTRIBUTE, "test");

        final Map<String, String> props = new HashMap<>();
        props.put("cacheKeyAttribute", "key");
        String flowFileContent = "content";
        runner.enqueue(flowFileContent.getBytes(StandardCharsets.UTF_8), props);

        runner.run();

        runner.assertAllFlowFilesTransferred(FetchDistributedMapCache.REL_SUCCESS, 1);
        runner.assertTransferCount(FetchDistributedMapCache.REL_SUCCESS, 1);

        final MockFlowFile outputFlowFile = runner.getFlowFilesForRelationship(FetchDistributedMapCache.REL_SUCCESS).get(0);
        outputFlowFile.assertAttributeEquals("test", "value");
        runner.clearTransferState();

    }

    @Test
    public void testToAttributeTooLong() throws IOException {
        service.put("key", "value", new FetchDistributedMapCache.StringSerializer(), new FetchDistributedMapCache.StringSerializer());
        runner.setProperty(FetchDistributedMapCache.CACHE_ENTRY_IDENTIFIER, "${cacheKeyAttribute}");
        runner.setProperty(FetchDistributedMapCache.PUT_CACHE_VALUE_IN_ATTRIBUTE, "test");
        runner.setProperty(FetchDistributedMapCache.PUT_ATTRIBUTE_MAX_LENGTH, "3");

        final Map<String, String> props = new HashMap<>();
        props.put("cacheKeyAttribute", "key");
        String flowFileContent = "content";
        runner.enqueue(flowFileContent.getBytes(StandardCharsets.UTF_8), props);

        runner.run();

        runner.assertAllFlowFilesTransferred(FetchDistributedMapCache.REL_SUCCESS, 1);
        runner.assertTransferCount(FetchDistributedMapCache.REL_SUCCESS, 1);

        final MockFlowFile outputFlowFile = runner.getFlowFilesForRelationship(FetchDistributedMapCache.REL_SUCCESS).get(0);
        outputFlowFile.assertAttributeEquals("test", "val");
        runner.clearTransferState();
    }

    @Test
    public void testMultipleKeysToAttributes() throws IOException {
        service.put("key1", "value1", new FetchDistributedMapCache.StringSerializer(), new FetchDistributedMapCache.StringSerializer());
        service.put("key2", "value2", new FetchDistributedMapCache.StringSerializer(), new FetchDistributedMapCache.StringSerializer());
        runner.setProperty(FetchDistributedMapCache.CACHE_ENTRY_IDENTIFIER, "key1, key2");
        // Not valid to set multiple keys without Put Cache Value In Attribute set
        runner.assertNotValid();
        runner.setProperty(FetchDistributedMapCache.PUT_CACHE_VALUE_IN_ATTRIBUTE, "test");
        runner.assertValid();

        final Map<String, String> props = new HashMap<>();
        runner.enqueue(new byte[]{}, props);

        runner.run();

        runner.assertAllFlowFilesTransferred(FetchDistributedMapCache.REL_SUCCESS, 1);
        runner.assertTransferCount(FetchDistributedMapCache.REL_SUCCESS, 1);

        final MockFlowFile outputFlowFile = runner.getFlowFilesForRelationship(FetchDistributedMapCache.REL_SUCCESS).get(0);
        outputFlowFile.assertAttributeEquals("test.key1", "value1");
        outputFlowFile.assertAttributeEquals("test.key2", "value2");
    }

    @Test
    public void testMultipleKeysOneNotFound() throws IOException {
        service.put("key1", "value1", new FetchDistributedMapCache.StringSerializer(), new FetchDistributedMapCache.StringSerializer());
        runner.setProperty(FetchDistributedMapCache.CACHE_ENTRY_IDENTIFIER, "key1, key2");
        // Not valid to set multiple keys without Put Cache Value In Attribute set
        runner.assertNotValid();
        runner.setProperty(FetchDistributedMapCache.PUT_CACHE_VALUE_IN_ATTRIBUTE, "test");
        runner.assertValid();

        final Map<String, String> props = new HashMap<>();
        runner.enqueue(new byte[]{}, props);

        runner.run();

        runner.assertAllFlowFilesTransferred(FetchDistributedMapCache.REL_NOT_FOUND, 1);
        runner.assertTransferCount(FetchDistributedMapCache.REL_NOT_FOUND, 1);

        final MockFlowFile outputFlowFile = runner.getFlowFilesForRelationship(FetchDistributedMapCache.REL_NOT_FOUND).get(0);
        outputFlowFile.assertAttributeEquals("test.key1", "value1");
    }


    private static class MockCacheClient extends AbstractControllerService implements DistributedMapCacheClient {
        private final ConcurrentMap<Object, Object> values = new ConcurrentHashMap<>();
        private boolean failOnCalls = false;

        public void setFailOnCalls(boolean failOnCalls) {
            this.failOnCalls = failOnCalls;
        }


        private void verifyNotFail() throws IOException {
            if (failOnCalls) {
                throw new IOException("Could not call to remote service because Unit Test marked service unavailable");
            }
        }

        @Override
        public <K, V> boolean putIfAbsent(final K key, final V value, final Serializer<K> keySerializer, final Serializer<V> valueSerializer) throws IOException {
            verifyNotFail();
            final Object retValue = values.putIfAbsent(key, value);
            return (retValue == null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <K, V> V getAndPutIfAbsent(final K key, final V value, final Serializer<K> keySerializer, final Serializer<V> valueSerializer,
            final Deserializer<V> valueDeserializer) throws IOException {
            verifyNotFail();
            return (V) values.putIfAbsent(key, value);
        }

        @Override
        public <K> boolean containsKey(final K key, final Serializer<K> keySerializer) throws IOException {
            verifyNotFail();
            return values.containsKey(key);
        }

        @Override
        public <K, V> void put(final K key, final V value, final Serializer<K> keySerializer, final Serializer<V> valueSerializer) throws IOException {
            verifyNotFail();
            values.put(key, value);
        }

        @Override
        public <K, V> V get(final K key, final Serializer<K> keySerializer, final Deserializer<V> valueDeserializer) throws IOException {
            verifyNotFail();
            if (values.containsKey(key)) {
                return (V) ((String) values.get(key)).getBytes();
            } else {
                return null;
            }
        }

        @Override
        public void close() {
        }

        @Override
        public <K> boolean remove(final K key, final Serializer<K> serializer) throws IOException {
            verifyNotFail();
            values.remove(key);
            return true;
        }
    }
}