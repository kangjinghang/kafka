/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.streams.state.internals;

import java.util.Collection;

import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.utils.Time;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.kstream.internals.SessionWindow;
import org.apache.kafka.streams.processor.internals.ProcessorRecordContext;
import org.apache.kafka.streams.query.Position;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.SessionStore;
import org.apache.kafka.streams.state.Stores;

import java.util.Collections;
import java.util.HashSet;

import org.junit.jupiter.api.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;

import static java.time.Duration.ofMillis;
import static java.util.Arrays.asList;
import static org.apache.kafka.common.utils.Utils.mkEntry;
import static org.apache.kafka.common.utils.Utils.mkMap;
import static org.apache.kafka.common.utils.Utils.mkSet;
import static org.apache.kafka.test.StreamsTestUtils.valuesToSet;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@RunWith(Parameterized.class)
public class RocksDBSessionStoreTest extends AbstractSessionBytesStoreTest {

    private static final String STORE_NAME = "rocksDB session store";

    @Parameter
    public StoreType storeType;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> getParamStoreType() {
        return asList(new Object[][] {
            {StoreType.RocksDBSessionStore},
            {StoreType.RocksDBTimeOrderedSessionStoreWithIndex},
            {StoreType.RocksDBTimeOrderedSessionStoreWithoutIndex}
        });
    }

    @Override
    StoreType getStoreType() {
        return storeType;
    }

    @Override
    <K, V> SessionStore<K, V> buildSessionStore(final long retentionPeriod,
                                                 final Serde<K> keySerde,
                                                 final Serde<V> valueSerde) {
        switch (storeType) {
            case RocksDBSessionStore: {
                return Stores.sessionStoreBuilder(
                    Stores.persistentSessionStore(
                        STORE_NAME,
                        ofMillis(retentionPeriod)),
                    keySerde,
                    valueSerde).build();
            }
            case RocksDBTimeOrderedSessionStoreWithIndex: {
                return Stores.sessionStoreBuilder(
                    new RocksDbTimeOrderedSessionBytesStoreSupplier(
                        STORE_NAME,
                        retentionPeriod,
                        true
                    ),
                    keySerde,
                    valueSerde
                ).build();
            }
            case RocksDBTimeOrderedSessionStoreWithoutIndex: {
                return Stores.sessionStoreBuilder(
                    new RocksDbTimeOrderedSessionBytesStoreSupplier(
                        STORE_NAME,
                        retentionPeriod,
                       false
                    ),
                    keySerde,
                    valueSerde
                ).build();
            }
            default:
                throw new IllegalStateException("Unknown StoreType: " + storeType);
        }
    }


    @Test
    public void shouldNotFetchExpiredSessions() {
        final long systemTime = Time.SYSTEM.milliseconds();
        sessionStore.put(new Windowed<>("p", new SessionWindow(systemTime - 3 * RETENTION_PERIOD, systemTime - 2 * RETENTION_PERIOD)), 1L);
        sessionStore.put(new Windowed<>("q", new SessionWindow(systemTime - 2 * RETENTION_PERIOD, systemTime - RETENTION_PERIOD)), 4L);
        sessionStore.put(new Windowed<>("r", new SessionWindow(systemTime - RETENTION_PERIOD, systemTime - RETENTION_PERIOD / 2)), 3L);
        sessionStore.put(new Windowed<>("p", new SessionWindow(systemTime - RETENTION_PERIOD, systemTime - RETENTION_PERIOD / 2)), 2L);
        try (final KeyValueIterator<Windowed<String>, Long> iterator =
                     sessionStore.findSessions("p", systemTime - 2 * RETENTION_PERIOD, systemTime - RETENTION_PERIOD)
        ) {
            assertEquals(mkSet(2L), valuesToSet(iterator));
        }
        try (final KeyValueIterator<Windowed<String>, Long> iterator =
                     sessionStore.backwardFindSessions("p", systemTime - 5 * RETENTION_PERIOD, systemTime - 4 * RETENTION_PERIOD)
        ) {
            assertFalse(iterator.hasNext());
        }
        try (final KeyValueIterator<Windowed<String>, Long> iterator =
                     sessionStore.findSessions("p", "r", systemTime - 5 * RETENTION_PERIOD, systemTime - 4 * RETENTION_PERIOD)
        ) {
            assertFalse(iterator.hasNext());
        }
        try (final KeyValueIterator<Windowed<String>, Long> iterator =
                     sessionStore.findSessions("p", "r", systemTime - RETENTION_PERIOD, systemTime - RETENTION_PERIOD / 2)
        ) {
            assertEquals(valuesToSet(iterator), mkSet(2L, 3L, 4L));
        }
        try (final KeyValueIterator<Windowed<String>, Long> iterator =
                     sessionStore.findSessions("p", "r", systemTime - 2 * RETENTION_PERIOD, systemTime - RETENTION_PERIOD)
        ) {
            assertEquals(valuesToSet(iterator), mkSet(2L, 3L, 4L));
        }
        try (final KeyValueIterator<Windowed<String>, Long> iterator =
                     sessionStore.backwardFindSessions("p", "r", systemTime - 2 * RETENTION_PERIOD, systemTime - RETENTION_PERIOD)
        ) {
            assertEquals(valuesToSet(iterator), mkSet(2L, 3L, 4L));
        }
    }

    @Test
    public void shouldRemoveExpired() {
        sessionStore.put(new Windowed<>("a", new SessionWindow(0, 0)), 1L);
        sessionStore.put(new Windowed<>("aa", new SessionWindow(0, SEGMENT_INTERVAL)), 2L);
        sessionStore.put(new Windowed<>("a", new SessionWindow(10, SEGMENT_INTERVAL)), 3L);

        // Advance stream time to expire the first record
        sessionStore.put(new Windowed<>("aa", new SessionWindow(10, 2 * SEGMENT_INTERVAL)), 4L);

        try (final KeyValueIterator<Windowed<String>, Long> iterator =
            sessionStore.findSessions("a", "b", 0L, Long.MAX_VALUE)
        ) {
            // The 2 records with values 2L and 3L are considered expired as
            // their end times < observed stream time - retentionPeriod + 1.
            assertEquals(valuesToSet(iterator), new HashSet<>(Collections.singletonList(4L)));
        }
    }

    @Test
    public void shouldMatchPositionAfterPut() {
        final MeteredSessionStore<String, Long> meteredSessionStore = (MeteredSessionStore<String, Long>) sessionStore;
        final ChangeLoggingSessionBytesStore changeLoggingSessionBytesStore = (ChangeLoggingSessionBytesStore) meteredSessionStore.wrapped();
        final WrappedStateStore rocksDBSessionStore = (WrappedStateStore) changeLoggingSessionBytesStore.wrapped();

        context.setRecordContext(new ProcessorRecordContext(0, 1, 0, "", new RecordHeaders()));
        sessionStore.put(new Windowed<String>("a", new SessionWindow(0, 0)), 1L);
        context.setRecordContext(new ProcessorRecordContext(0, 2, 0, "", new RecordHeaders()));
        sessionStore.put(new Windowed<String>("aa", new SessionWindow(0, SEGMENT_INTERVAL)), 2L);
        context.setRecordContext(new ProcessorRecordContext(0, 3, 0, "", new RecordHeaders()));
        sessionStore.put(new Windowed<String>("a", new SessionWindow(10, SEGMENT_INTERVAL)), 3L);

        final Position expected = Position.fromMap(mkMap(mkEntry("", mkMap(mkEntry(0, 3L)))));
        final Position actual = rocksDBSessionStore.getPosition();
        assertEquals(expected, actual);
    }
}