package io.floci.gcp.services.datastore;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.datastore.v1.AggregationQuery;
import com.google.datastore.v1.ArrayValue;
import com.google.datastore.v1.Entity;
import com.google.datastore.v1.Filter;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.KindExpression;
import com.google.datastore.v1.Mutation;
import com.google.datastore.v1.PartitionId;
import com.google.datastore.v1.PropertyFilter;
import com.google.datastore.v1.PropertyReference;
import com.google.datastore.v1.Query;
import com.google.datastore.v1.Value;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import com.google.protobuf.NullValue;
import com.google.protobuf.Timestamp;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.storage.InMemoryStorage;
import io.floci.gcp.services.datastore.model.StoredEntity;
import io.floci.gcp.services.datastore.model.StoredProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class DatastoreServiceTest {

    private DatastoreService service;
    private static final String PROJECT = "p1";

    @BeforeEach
    void setUp() {
        service = new DatastoreService(new InMemoryStorage<>());
    }

    private Key namedKey(String kind, String name) {
        return Key.newBuilder()
                .setPartitionId(PartitionId.newBuilder().setProjectId(PROJECT).build())
                .addPath(Key.PathElement.newBuilder().setKind(kind).setName(name).build())
                .build();
    }

    @Test
    void upsertAndLookupEntityByName() {
        Key key = namedKey("Person", "alice");
        Entity entity = Entity.newBuilder()
                .setKey(key)
                .putProperties("name", Value.newBuilder().setStringValue("Alice").build())
                .build();

        service.applyMutation(PROJECT, Mutation.newBuilder().setUpsert(entity).build(), Instant.now());

        Optional<StoredEntity> result = service.lookupEntity(PROJECT, key);
        assertTrue(result.isPresent());
        assertEquals("Person", result.get().getKind());
        assertEquals("alice", result.get().getKeyName());
    }

    @Test
    void geoPointValueReadsBackUnchanged() {
        Key key = namedKey("Place", "hq");
        Value.Builder at = Value.newBuilder();
        at.getGeoPointValueBuilder().setLatitude(37.422).setLongitude(-122.084);
        Entity entity = Entity.newBuilder()
                .setKey(key)
                .putProperties("at", at.build())
                .build();
        service.applyMutation(PROJECT, Mutation.newBuilder().setUpsert(entity).build(), Instant.now());

        Value stored = service.lookupEntity(PROJECT, key).orElseThrow().getProperties().get("at").toProto();

        assertEquals(Value.ValueTypeCase.GEO_POINT_VALUE, stored.getValueTypeCase());
        assertEquals(37.422, stored.getGeoPointValue().getLatitude());
        assertEquals(-122.084, stored.getGeoPointValue().getLongitude());
    }

    @Test
    void keyValueReadsBackWithItsNamespaceAncestorsAndId() {
        Key ref = Key.newBuilder()
                .setPartitionId(PartitionId.newBuilder().setProjectId(PROJECT).setNamespaceId("tenant-a"))
                .addPath(Key.PathElement.newBuilder().setKind("Org").setName("acme"))
                .addPath(Key.PathElement.newBuilder().setKind("User").setId(12345))
                .build();
        Value value = Value.newBuilder().setKeyValue(ref).build();
        upsertProperty("Link", "l1", "target", value);

        assertEquals(value, readBack("Link", "l1").getPropertiesOrThrow("target"));
    }

    @Test
    void entityValueReadsBackWithItsKeyAndProperties() {
        Value value = Value.newBuilder()
                .setEntityValue(Entity.newBuilder()
                        .setKey(namedKey("Address", "home"))
                        .putProperties("city", Value.newBuilder().setStringValue("Lagos").build())
                        .putProperties("owner", Value.newBuilder().setKeyValue(namedKey("Person", "alice")).build())
                        .putProperties("geo", Value.newBuilder()
                                .setEntityValue(Entity.newBuilder()
                                        .putProperties("zip", Value.newBuilder().setIntegerValue(100001).build()))
                                .build()))
                .build();
        upsertProperty("Person", "bob", "address", value);

        assertEquals(value, readBack("Person", "bob").getPropertiesOrThrow("address"));
    }

    @Test
    void keyAndEntityValuesInsideAnArrayReadBack() {
        Value value = Value.newBuilder()
                .setArrayValue(ArrayValue.newBuilder()
                        .addValues(Value.newBuilder().setKeyValue(namedKey("Ref", "a")))
                        .addValues(Value.newBuilder().setEntityValue(Entity.newBuilder()
                                .putProperties("n", Value.newBuilder().setIntegerValue(1).build()))))
                .build();
        upsertProperty("Bag", "b1", "items", value);

        assertEquals(value, readBack("Bag", "b1").getPropertiesOrThrow("items"));
    }

    @Test
    void equalFilterOnKeyTellsAnIdFromANameWithTheSameDigits() {
        Value byId = Value.newBuilder().setKeyValue(Key.newBuilder()
                .setPartitionId(PartitionId.newBuilder().setProjectId(PROJECT))
                .addPath(Key.PathElement.newBuilder().setKind("Ref").setId(12345))).build();
        Value byName = Value.newBuilder().setKeyValue(namedKey("Ref", "12345")).build();
        upsertProperty("Link", "id", "target", byId);
        upsertProperty("Link", "name", "target", byName);

        assertEquals(List.of("id"), namesOf(runPropertyQuery("Link", "target", PropertyFilter.Operator.EQUAL, byId)));
        assertEquals(List.of("name"), namesOf(runPropertyQuery("Link", "target", PropertyFilter.Operator.EQUAL, byName)));
    }

    @Test
    void keyValueSurvivesTheJsonRoundTripPersistentStorageUses() throws Exception {
        Value value = Value.newBuilder()
                .setEntityValue(Entity.newBuilder()
                        .setKey(namedKey("Address", "home"))
                        .putProperties("owner", Value.newBuilder().setKeyValue(namedKey("Person", "alice")).build()))
                .build();
        upsertProperty("Person", "bob", "address", value);
        StoredEntity stored = service.lookupEntity(PROJECT, namedKey("Person", "bob")).orElseThrow();

        ObjectMapper mapper = new ObjectMapper();
        StoredEntity reloaded = mapper.readValue(mapper.writeValueAsString(stored), StoredEntity.class);

        assertEquals(value, service.toProto(reloaded).getPropertiesOrThrow("address"));
    }

    @Test
    void aDamagedStoredKeyReadsBackAsNullWithoutFailingTheRead() {
        StoredProperty damaged = new StoredProperty();
        damaged.setType("key");
        damaged.setKeyValue("not base64!");

        assertEquals(Value.ValueTypeCase.NULL_VALUE, damaged.toProto().getValueTypeCase());
        assertFalse(damaged.matchesEqual(Value.newBuilder().setKeyValue(namedKey("Ref", "alpha")).build()));
    }

    @Test
    void keyStoredInTheEarlierPathFormStillMatchesAnEqualityFilter() {
        // Entities persisted before key values were stored losslessly hold only the flattened path.
        StoredProperty legacy = new StoredProperty();
        legacy.setType("key");
        legacy.setStringValue(PROJECT + "//Ref/alpha");

        assertTrue(legacy.matchesEqual(Value.newBuilder().setKeyValue(namedKey("Ref", "alpha")).build()));
        assertFalse(legacy.matchesEqual(Value.newBuilder().setKeyValue(namedKey("Ref", "beta")).build()));
    }

    @Test
    void meaningAndExcludeFromIndexesReadBackOnEveryValue() {
        Value value = Value.newBuilder()
                .setEntityValue(Entity.newBuilder()
                        .putProperties("bio", Value.newBuilder()
                                .setStringValue("long text").setMeaning(15).setExcludeFromIndexes(true).build())
                        .putProperties("tags", Value.newBuilder()
                                .setArrayValue(ArrayValue.newBuilder()
                                        .addValues(Value.newBuilder().setStringValue("a").setExcludeFromIndexes(true))
                                        .addValues(Value.newBuilder().setIntegerValue(1).setMeaning(9)))
                                .build()))
                .setExcludeFromIndexes(true)
                .build();
        upsertProperty("Person", "carol", "profile", value);

        assertEquals(value, readBack("Person", "carol").getPropertiesOrThrow("profile"));
    }

    @Test
    void meaningAndExcludeFromIndexesSurviveTheJsonRoundTripPersistentStorageUses() throws Exception {
        Value value = Value.newBuilder()
                .setStringValue("long text").setMeaning(15).setExcludeFromIndexes(true).build();
        upsertProperty("Person", "dave", "bio", value);
        StoredEntity stored = service.lookupEntity(PROJECT, namedKey("Person", "dave")).orElseThrow();

        ObjectMapper mapper = new ObjectMapper();
        StoredEntity reloaded = mapper.readValue(mapper.writeValueAsString(stored), StoredEntity.class);

        assertEquals(value, service.toProto(reloaded).getPropertiesOrThrow("bio"));
    }

    @Test
    void aPropertyStoredWithoutMeaningOrExcludeFromIndexesStillLoads() throws Exception {
        // The JSON shape entities were persisted in before these two fields existed.
        StoredProperty legacy = new ObjectMapper().readValue(
                "{\"type\":\"string\",\"stringValue\":\"hello\"}", StoredProperty.class);

        assertEquals(Value.newBuilder().setStringValue("hello").build(), legacy.toProto());
    }

    @Test
    void lookupMissingEntityReturnsEmpty() {
        Key key = namedKey("Person", "missing");
        Optional<StoredEntity> result = service.lookupEntity(PROJECT, key);
        assertTrue(result.isEmpty());
    }

    @Test
    void insertDuplicateThrowsAlreadyExists() {
        Key key = namedKey("Thing", "x");
        Entity entity = Entity.newBuilder().setKey(key).build();
        service.applyMutation(PROJECT, Mutation.newBuilder().setInsert(entity).build(), Instant.now());

        GcpException ex = assertThrows(GcpException.class,
                () -> service.applyMutation(PROJECT,
                        Mutation.newBuilder().setInsert(entity).build(), Instant.now()));
        assertEquals("ALREADY_EXISTS", ex.getGcpStatus());
    }

    @Test
    void updateMissingEntityThrowsNotFound() {
        Key key = namedKey("Thing", "missing");
        Entity entity = Entity.newBuilder().setKey(key).build();

        GcpException ex = assertThrows(GcpException.class,
                () -> service.applyMutation(PROJECT,
                        Mutation.newBuilder().setUpdate(entity).build(), Instant.now()));
        assertEquals("NOT_FOUND", ex.getGcpStatus());
    }

    @Test
    void deleteEntityRemovedFromLookup() {
        Key key = namedKey("Person", "bob");
        Entity entity = Entity.newBuilder().setKey(key).build();
        service.applyMutation(PROJECT, Mutation.newBuilder().setUpsert(entity).build(), Instant.now());

        service.applyMutation(PROJECT, Mutation.newBuilder().setDelete(key).build(), Instant.now());

        assertTrue(service.lookupEntity(PROJECT, key).isEmpty());
    }

    @Test
    void runQueryReturnsEntitiesOfKind() {
        for (String name : List.of("a", "b")) {
            Key key = namedKey("Item", name);
            Entity entity = Entity.newBuilder().setKey(key).build();
            service.applyMutation(PROJECT, Mutation.newBuilder().setUpsert(entity).build(), Instant.now());
        }

        Query query = Query.newBuilder()
                .addKind(KindExpression.newBuilder().setName("Item").build())
                .build();

        List<StoredEntity> results = service.runQuery(PROJECT, null, query);
        assertEquals(2, results.size());
    }

    @Test
    void allocateIdsReturnsFilledKeys() {
        Key incomplete = Key.newBuilder()
                .setPartitionId(PartitionId.newBuilder().setProjectId(PROJECT).build())
                .addPath(Key.PathElement.newBuilder().setKind("Widget").build())
                .build();

        List<Key> allocated = service.allocateIds(PROJECT, List.of(incomplete));
        assertEquals(1, allocated.size());
        assertTrue(allocated.get(0).getPath(0).getId() > 0);
    }

    @Test
    void beginTransactionReturnsByteArray() {
        byte[] txn = service.beginTransaction();
        assertNotNull(txn);
        assertTrue(txn.length > 0);
    }

    @Test
    void equalFilterOnTimestampReturnsOnlyMatchingEntity() {
        Value first = Value.newBuilder()
                .setTimestampValue(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(100).build())
                .build();
        Value second = Value.newBuilder()
                .setTimestampValue(Timestamp.newBuilder().setSeconds(1_700_000_000L).setNanos(200).build())
                .build();
        upsertProperty("Event", "first", "seen", first);
        upsertProperty("Event", "second", "seen", second);

        List<StoredEntity> equal = runPropertyQuery("Event", "seen", PropertyFilter.Operator.EQUAL, first);
        assertEquals(List.of("first"), namesOf(equal));

        List<StoredEntity> notEqual = runPropertyQuery("Event", "seen", PropertyFilter.Operator.NOT_EQUAL, first);
        assertEquals(List.of("second"), namesOf(notEqual));
    }

    @Test
    void equalFilterOnBlobReturnsOnlyMatchingEntity() {
        Value first = Value.newBuilder().setBlobValue(ByteString.copyFrom(new byte[] {1, 2, 3})).build();
        Value second = Value.newBuilder().setBlobValue(ByteString.copyFrom(new byte[] {4, 5})).build();
        upsertProperty("BlobKind", "first", "payload", first);
        upsertProperty("BlobKind", "second", "payload", second);

        List<StoredEntity> results = runPropertyQuery(
                "BlobKind", "payload", PropertyFilter.Operator.EQUAL, first);
        assertEquals(List.of("first"), namesOf(results));
    }

    @Test
    void equalFilterOnKeyMatchesSamePathOnly() {
        Value first = Value.newBuilder().setKeyValue(namedKey("Ref", "alpha")).build();
        Value second = Value.newBuilder().setKeyValue(namedKey("Ref", "beta")).build();
        upsertProperty("Link", "first", "target", first);
        upsertProperty("Link", "second", "target", second);

        List<StoredEntity> results = runPropertyQuery("Link", "target", PropertyFilter.Operator.EQUAL, first);
        assertEquals(List.of("first"), namesOf(results));
    }

    @Test
    void equalFilterOnEntityMatchesSamePropertiesOnly() {
        Value first = Value.newBuilder()
                .setEntityValue(Entity.newBuilder()
                        .putProperties("n", Value.newBuilder().setStringValue("a").build())
                        .build())
                .build();
        Value extra = Value.newBuilder()
                .setEntityValue(Entity.newBuilder()
                        .putProperties("n", Value.newBuilder().setStringValue("a").build())
                        .putProperties("extra", Value.newBuilder().setIntegerValue(1).build())
                        .build())
                .build();
        upsertProperty("Wrap", "first", "body", first);
        upsertProperty("Wrap", "extra", "body", extra);

        List<StoredEntity> results = runPropertyQuery("Wrap", "body", PropertyFilter.Operator.EQUAL, first);
        assertEquals(List.of("first"), namesOf(results));
    }

    @Test
    void equalFilterOnArrayMatchesWhenAnyElementEquals() {
        upsertProperty("Post", "tagged", "tags", arrayValue("fun", "programming"));
        upsertProperty("Post", "other", "tags", arrayValue("work"));

        Value fun = Value.newBuilder().setStringValue("fun").build();
        assertEquals(List.of("tagged"), namesOf(
                runPropertyQuery("Post", "tags", PropertyFilter.Operator.EQUAL, fun)));

        Value programming = Value.newBuilder().setStringValue("programming").build();
        assertEquals(List.of("tagged"), namesOf(
                runPropertyQuery("Post", "tags", PropertyFilter.Operator.EQUAL, programming)));

        Value missing = Value.newBuilder().setStringValue("missing").build();
        List<StoredEntity> absent = runPropertyQuery("Post", "tags", PropertyFilter.Operator.EQUAL, missing);
        assertTrue(absent.isEmpty());
    }

    @Test
    void equalFilterOnScalarPropertyStillMatchesByEquality() {
        Value title = Value.newBuilder().setStringValue("fun").build();
        upsertProperty("Note", "match", "title", title);
        upsertProperty("Note", "other", "title", Value.newBuilder().setStringValue("work").build());

        List<StoredEntity> results = runPropertyQuery("Note", "title", PropertyFilter.Operator.EQUAL, title);
        assertEquals(List.of("match"), namesOf(results));
    }

    @Test
    void outOfRangeTimestampFilterReturnsNoEntities() {
        Value stored = Value.newBuilder()
                .setTimestampValue(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .build();
        upsertProperty("Event", "only", "seen", stored);
        Value filter = Value.newBuilder()
                .setTimestampValue(Timestamp.newBuilder().setSeconds(Long.MAX_VALUE).build())
                .build();

        List<StoredEntity> results = runPropertyQuery("Event", "seen", PropertyFilter.Operator.EQUAL, filter);
        assertTrue(results.isEmpty());
    }

    @Test
    void timestampFilterAgainstStringPropertyReturnsNoEntities() {
        upsertProperty("Event", "only", "seen", Value.newBuilder().setStringValue("1700000000").build());
        Value filter = Value.newBuilder()
                .setTimestampValue(Timestamp.newBuilder().setSeconds(1_700_000_000L).build())
                .build();

        List<StoredEntity> results = runPropertyQuery("Event", "seen", PropertyFilter.Operator.EQUAL, filter);
        assertTrue(results.isEmpty());
    }

    @Test
    void equalFilterStillMatchesBooleanIntegerDoubleStringAndNull() {
        Value yes = Value.newBuilder().setBooleanValue(true).build();
        upsertProperty("Bools", "yes", "v", yes);
        upsertProperty("Bools", "no", "v", Value.newBuilder().setBooleanValue(false).build());
        assertEquals(List.of("yes"), namesOf(runPropertyQuery("Bools", "v", PropertyFilter.Operator.EQUAL, yes)));

        Value one = Value.newBuilder().setIntegerValue(1L).build();
        upsertProperty("Ints", "one", "v", one);
        upsertProperty("Ints", "two", "v", Value.newBuilder().setIntegerValue(2L).build());
        assertEquals(List.of("one"), namesOf(runPropertyQuery("Ints", "v", PropertyFilter.Operator.EQUAL, one)));

        Value half = Value.newBuilder().setDoubleValue(1.5).build();
        upsertProperty("Doubles", "half", "v", half);
        upsertProperty("Doubles", "other", "v", Value.newBuilder().setDoubleValue(2.5).build());
        assertEquals(List.of("half"), namesOf(runPropertyQuery("Doubles", "v", PropertyFilter.Operator.EQUAL, half)));

        Value name = Value.newBuilder().setStringValue("Ada").build();
        upsertProperty("Strings", "ada", "v", name);
        upsertProperty("Strings", "bob", "v", Value.newBuilder().setStringValue("Bob").build());
        assertEquals(List.of("ada"), namesOf(runPropertyQuery("Strings", "v", PropertyFilter.Operator.EQUAL, name)));

        Value nil = Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build();
        upsertProperty("Nulls", "nil", "v", nil);
        upsertProperty("Nulls", "text", "v", Value.newBuilder().setStringValue("x").build());
        assertEquals(List.of("nil"), namesOf(runPropertyQuery("Nulls", "v", PropertyFilter.Operator.EQUAL, nil)));
    }

    @Test
    void sumAndAvgAggregateThePropertyInsteadOfCountingEntities() {
        seedPrices("Item", 10, 20);

        Map<String, Value> result = aggregate("Item", count("n"), sum("price", "total"), avg("price", "mean"));

        assertEquals(Value.newBuilder().setIntegerValue(2).build(), result.get("n"));
        assertEquals(Value.newBuilder().setIntegerValue(30).build(), result.get("total"));
        assertEquals(Value.newBuilder().setDoubleValue(15.0).build(), result.get("mean"));
    }

    @Test
    void nonNumericAndMissingValuesAreSkipped() {
        seedPrices("Item", 10, 20);
        upsertProperty("Item", "text", "price", Value.newBuilder().setStringValue("free").build());
        upsertProperty("Item", "null", "price", Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build());
        upsertProperty("Item", "none", "other", Value.newBuilder().setIntegerValue(99).build());

        Map<String, Value> result = aggregate("Item", count("n"), sum("price", "total"), avg("price", "mean"));

        assertEquals(5, result.get("n").getIntegerValue());
        assertEquals(30, result.get("total").getIntegerValue());
        assertEquals(15.0, result.get("mean").getDoubleValue());
    }

    @Test
    void sumIsADoubleWhenAnyValueIsADoubleOrTheIntegersOverflow() {
        upsertProperty("Mixed", "a", "price", Value.newBuilder().setIntegerValue(10).build());
        upsertProperty("Mixed", "b", "price", Value.newBuilder().setDoubleValue(2.5).build());
        upsertProperty("Big", "a", "n", Value.newBuilder().setIntegerValue(Long.MAX_VALUE).build());
        upsertProperty("Big", "b", "n", Value.newBuilder().setIntegerValue(1).build());

        assertEquals(Value.newBuilder().setDoubleValue(12.5).build(),
                aggregate("Mixed", sum("price", "total")).get("total"));
        assertEquals(Value.newBuilder().setDoubleValue((double) Long.MAX_VALUE + 1).build(),
                aggregate("Big", sum("n", "total")).get("total"));
    }

    @Test
    void sumStaysAnIntegerWhenAnIntermediateOverflowCancelsOut() {
        upsertProperty("Swing", "a", "n", Value.newBuilder().setIntegerValue(Long.MAX_VALUE).build());
        upsertProperty("Swing", "b", "n", Value.newBuilder().setIntegerValue(1).build());
        upsertProperty("Swing", "c", "n", Value.newBuilder().setIntegerValue(-Long.MAX_VALUE).build());
        // Integers are added exactly, so the result is 1 whichever order the entities are summed in.
        Map<String, Value> result = aggregate("Swing", sum("n", "total"));

        assertEquals(Value.newBuilder().setIntegerValue(1).build(), result.get("total"));
    }

    @Test
    void sumOfNothingIsZeroAndAvgOfNothingIsNull() {
        Map<String, Value> result = aggregate("Empty", sum("price", "total"), avg("price", "mean"));

        assertEquals(Value.newBuilder().setIntegerValue(0).build(), result.get("total"));
        assertEquals(Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build(), result.get("mean"));
    }

    @Test
    void nanInTheAggregatedValuesMakesSumAndAvgNan() {
        upsertProperty("Nan", "a", "price", Value.newBuilder().setIntegerValue(1).build());
        upsertProperty("Nan", "b", "price", Value.newBuilder().setDoubleValue(Double.NaN).build());

        Map<String, Value> result = aggregate("Nan", sum("price", "total"), avg("price", "mean"));

        assertTrue(Double.isNaN(result.get("total").getDoubleValue()));
        assertTrue(Double.isNaN(result.get("mean").getDoubleValue()));
    }

    @Test
    void sumAndAvgReadADotDelimitedPathThroughAnEmbeddedEntity() {
        for (int i = 1; i <= 2; i++) {
            upsertProperty("Order", "o" + i, "item", Value.newBuilder()
                    .setEntityValue(Entity.newBuilder()
                            .putProperties("price", Value.newBuilder().setIntegerValue(i * 10L).build()))
                    .build());
        }

        Map<String, Value> result = aggregate("Order", sum("item.price", "total"), avg("item.price", "mean"));

        assertEquals(30, result.get("total").getIntegerValue());
        assertEquals(15.0, result.get("mean").getDoubleValue());
    }

    @Test
    void countUpToCapsTheCountAndZeroCountsNothing() {
        seedPrices("Item", 10, 20, 30);

        Map<String, Value> result = aggregate("Item", countUpTo("two", 2), countUpTo("zero", 0), countUpTo("ten", 10));

        assertEquals(2, result.get("two").getIntegerValue());
        assertEquals(0, result.get("zero").getIntegerValue());
        assertEquals(3, result.get("ten").getIntegerValue());
    }

    @Test
    void aNegativeCountUpToIsRejected() {
        GcpException e = assertThrows(GcpException.class, () -> aggregate("Item", countUpTo("n", -1)));
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, e.getGrpcCode());
    }

    @Test
    void anAggregationWithoutAnOperatorIsRejected() {
        GcpException e = assertThrows(GcpException.class,
                () -> aggregate("Item", AggregationQuery.Aggregation.newBuilder().setAlias("n").build()));
        assertEquals(io.grpc.Status.Code.INVALID_ARGUMENT, e.getGrpcCode());
    }

    @Test
    void unnamedAggregationsGetTheirOwnDefaultAliases() {
        seedPrices("Item", 10, 20);

        Map<String, Value> result = aggregate("Item",
                countUpTo("count_up_to_1", 1), sum("price", ""), count("named"), avg("price", ""));

        assertEquals(List.of("count_up_to_1", "property_1", "named", "property_2"), List.copyOf(result.keySet()));
        assertEquals(30, result.get("property_1").getIntegerValue());
        assertEquals(15.0, result.get("property_2").getDoubleValue());
    }

    @Test
    void theNestedQueryFilterAndLimitApplyBeforeAggregating() {
        seedPrices("Item", 10, 20, 30);
        Query cheapest = Query.newBuilder()
                .addKind(KindExpression.newBuilder().setName("Item"))
                .setFilter(Filter.newBuilder().setPropertyFilter(PropertyFilter.newBuilder()
                        .setProperty(PropertyReference.newBuilder().setName("price"))
                        .setOp(PropertyFilter.Operator.NOT_EQUAL)
                        .setValue(Value.newBuilder().setIntegerValue(30))))
                .setLimit(com.google.protobuf.Int32Value.of(1))
                .build();

        Map<String, Value> result = service.runAggregationQuery(PROJECT, null, AggregationQuery.newBuilder()
                .setNestedQuery(cheapest).addAggregations(count("n")).build());

        assertEquals(1, result.get("n").getIntegerValue());
    }

    private void upsertProperty(String kind, String name, String property, Value value) {
        Entity entity = Entity.newBuilder()
                .setKey(namedKey(kind, name))
                .putProperties(property, value)
                .build();
        service.applyMutation(PROJECT, Mutation.newBuilder().setUpsert(entity).build(), Instant.now());
    }

    private List<StoredEntity> runPropertyQuery(
            String kind, String property, PropertyFilter.Operator op, Value value) {
        Query query = Query.newBuilder()
                .addKind(KindExpression.newBuilder().setName(kind).build())
                .setFilter(Filter.newBuilder()
                        .setPropertyFilter(PropertyFilter.newBuilder()
                                .setProperty(PropertyReference.newBuilder().setName(property).build())
                                .setOp(op)
                                .setValue(value)
                                .build())
                        .build())
                .build();
        return service.runQuery(PROJECT, null, query);
    }

    private static Value arrayValue(String... elements) {
        ArrayValue.Builder array = ArrayValue.newBuilder();
        for (String element : elements) {
            array.addValues(Value.newBuilder().setStringValue(element).build());
        }
        return Value.newBuilder().setArrayValue(array.build()).build();
    }

    private void seedPrices(String kind, long... prices) {
        for (int i = 0; i < prices.length; i++) {
            upsertProperty(kind, "e" + i, "price", Value.newBuilder().setIntegerValue(prices[i]).build());
        }
    }

    private Map<String, Value> aggregate(String kind, AggregationQuery.Aggregation... aggregations) {
        return service.runAggregationQuery(PROJECT, null, AggregationQuery.newBuilder()
                .setNestedQuery(Query.newBuilder().addKind(KindExpression.newBuilder().setName(kind)))
                .addAllAggregations(List.of(aggregations))
                .build());
    }

    private static AggregationQuery.Aggregation count(String alias) {
        return AggregationQuery.Aggregation.newBuilder()
                .setCount(AggregationQuery.Aggregation.Count.getDefaultInstance()).setAlias(alias).build();
    }

    private static AggregationQuery.Aggregation countUpTo(String alias, long upTo) {
        return AggregationQuery.Aggregation.newBuilder()
                .setCount(AggregationQuery.Aggregation.Count.newBuilder().setUpTo(Int64Value.of(upTo)))
                .setAlias(alias).build();
    }

    private static AggregationQuery.Aggregation sum(String property, String alias) {
        return AggregationQuery.Aggregation.newBuilder()
                .setSum(AggregationQuery.Aggregation.Sum.newBuilder()
                        .setProperty(PropertyReference.newBuilder().setName(property)))
                .setAlias(alias).build();
    }

    private static AggregationQuery.Aggregation avg(String property, String alias) {
        return AggregationQuery.Aggregation.newBuilder()
                .setAvg(AggregationQuery.Aggregation.Avg.newBuilder()
                        .setProperty(PropertyReference.newBuilder().setName(property)))
                .setAlias(alias).build();
    }

    private Entity readBack(String kind, String name) {
        return service.toProto(service.lookupEntity(PROJECT, namedKey(kind, name)).orElseThrow());
    }

    private static List<String> namesOf(List<StoredEntity> entities) {
        return entities.stream().map(StoredEntity::getKeyName).toList();
    }
}
