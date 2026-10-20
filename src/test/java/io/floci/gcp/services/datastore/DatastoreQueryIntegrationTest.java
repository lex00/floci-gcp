package io.floci.gcp.services.datastore;

import com.google.datastore.v1.AggregationQuery;
import com.google.datastore.v1.CommitRequest;
import com.google.datastore.v1.CommitResponse;
import com.google.datastore.v1.Entity;
import com.google.datastore.v1.GqlQuery;
import com.google.datastore.v1.Key;
import com.google.datastore.v1.KindExpression;
import com.google.datastore.v1.Mutation;
import com.google.datastore.v1.PartitionId;
import com.google.datastore.v1.PropertyReference;
import com.google.datastore.v1.Query;
import com.google.datastore.v1.RunAggregationQueryRequest;
import com.google.datastore.v1.RunAggregationQueryResponse;
import com.google.datastore.v1.RunQueryRequest;
import com.google.datastore.v1.RunQueryResponse;
import com.google.datastore.v1.Value;
import com.google.protobuf.Message;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class DatastoreQueryIntegrationTest {

    private static final String PROJECT = "ds-query-it";

    private static final MethodDescriptor<CommitRequest, CommitResponse> COMMIT =
            method("Commit", CommitRequest.getDefaultInstance(), CommitResponse.getDefaultInstance());
    private static final MethodDescriptor<RunQueryRequest, RunQueryResponse> RUN_QUERY =
            method("RunQuery", RunQueryRequest.getDefaultInstance(), RunQueryResponse.getDefaultInstance());
    private static final MethodDescriptor<RunAggregationQueryRequest, RunAggregationQueryResponse> RUN_AGGREGATION_QUERY =
            method("RunAggregationQuery", RunAggregationQueryRequest.getDefaultInstance(),
                    RunAggregationQueryResponse.getDefaultInstance());

    @TestHTTPResource
    URI endpoint;

    private ManagedChannel channel;

    @BeforeEach
    void openChannel() {
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
    }

    @AfterEach
    void closeChannel() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void grpcRunAggregationQueryAggregatesTheProperty() {
        seedPrices("GrpcAgg", 10, 20);

        RunAggregationQueryResponse response = ClientCalls.blockingUnaryCall(channel, RUN_AGGREGATION_QUERY,
                CallOptions.DEFAULT, aggregationRequest("GrpcAgg"));

        assertAggregates(response);
    }

    @Test
    void restRunAggregationQueryAggregatesTheProperty() throws Exception {
        seedPrices("RestAgg", 10, 20);

        byte[] body = given().urlEncodingEnabled(false).contentType("application/x-protobuf")
                .body(aggregationRequest("RestAgg").toByteArray())
                .when().post("/v1/projects/" + PROJECT + ":runAggregationQuery")
                .then().statusCode(200)
                .extract().asByteArray();

        assertAggregates(RunAggregationQueryResponse.parseFrom(body));
    }

    @Test
    void grpcRunQueryRunsAGqlQuery() {
        seedPrices("GrpcGql", 10, 20);

        RunQueryResponse response = ClientCalls.blockingUnaryCall(channel, RUN_QUERY, CallOptions.DEFAULT,
                RunQueryRequest.newBuilder()
                        .setProjectId(PROJECT)
                        .setGqlQuery(GqlQuery.newBuilder()
                                .setQueryString("SELECT * FROM GrpcGql WHERE price = 20")
                                .setAllowLiterals(true))
                        .build());

        assertEquals(1, response.getBatch().getEntityResultsCount());
        assertEquals(20, response.getBatch().getEntityResults(0).getEntity()
                .getPropertiesOrThrow("price").getIntegerValue());
    }

    @Test
    void aGqlAggregationQueryIsRejectedAsUnimplementedOnBothTransports() {
        RunAggregationQueryRequest request = RunAggregationQueryRequest.newBuilder()
                .setProjectId(PROJECT)
                .setGqlQuery(GqlQuery.newBuilder()
                        .setQueryString("AGGREGATE COUNT(*) OVER (SELECT * FROM Anything)")
                        .setAllowLiterals(true))
                .build();

        StatusRuntimeException grpc = assertThrows(StatusRuntimeException.class,
                () -> ClientCalls.blockingUnaryCall(channel, RUN_AGGREGATION_QUERY, CallOptions.DEFAULT, request));
        assertEquals(Status.Code.UNIMPLEMENTED, grpc.getStatus().getCode());

        given().urlEncodingEnabled(false).contentType("application/x-protobuf").body(request.toByteArray())
                .when().post("/v1/projects/" + PROJECT + ":runAggregationQuery")
                .then().statusCode(501);
    }

    private void seedPrices(String kind, long... prices) {
        CommitRequest.Builder commit = CommitRequest.newBuilder()
                .setProjectId(PROJECT)
                .setMode(CommitRequest.Mode.NON_TRANSACTIONAL);
        for (int i = 0; i < prices.length; i++) {
            commit.addMutations(Mutation.newBuilder().setUpsert(Entity.newBuilder()
                    .setKey(Key.newBuilder()
                            .setPartitionId(PartitionId.newBuilder().setProjectId(PROJECT))
                            .addPath(Key.PathElement.newBuilder().setKind(kind).setName("e" + i)))
                    .putProperties("price", Value.newBuilder().setIntegerValue(prices[i]).build())));
        }
        ClientCalls.blockingUnaryCall(channel, COMMIT, CallOptions.DEFAULT, commit.build());
    }

    /** COUNT, SUM and AVG with aliases, then an unnamed SUM and AVG that take the default aliases. */
    private static RunAggregationQueryRequest aggregationRequest(String kind) {
        PropertyReference price = PropertyReference.newBuilder().setName("price").build();
        return RunAggregationQueryRequest.newBuilder()
                .setProjectId(PROJECT)
                .setAggregationQuery(AggregationQuery.newBuilder()
                        .setNestedQuery(Query.newBuilder().addKind(KindExpression.newBuilder().setName(kind)))
                        .addAggregations(AggregationQuery.Aggregation.newBuilder()
                                .setCount(AggregationQuery.Aggregation.Count.getDefaultInstance()).setAlias("n"))
                        .addAggregations(AggregationQuery.Aggregation.newBuilder()
                                .setSum(AggregationQuery.Aggregation.Sum.newBuilder().setProperty(price))
                                .setAlias("total"))
                        .addAggregations(AggregationQuery.Aggregation.newBuilder()
                                .setAvg(AggregationQuery.Aggregation.Avg.newBuilder().setProperty(price))
                                .setAlias("mean"))
                        .addAggregations(AggregationQuery.Aggregation.newBuilder()
                                .setSum(AggregationQuery.Aggregation.Sum.newBuilder().setProperty(price)))
                        .addAggregations(AggregationQuery.Aggregation.newBuilder()
                                .setAvg(AggregationQuery.Aggregation.Avg.newBuilder().setProperty(price))))
                .build();
    }

    private static void assertAggregates(RunAggregationQueryResponse response) {
        assertEquals(1, response.getBatch().getAggregationResultsCount());
        Map<String, Value> result = response.getBatch().getAggregationResults(0).getAggregatePropertiesMap();
        assertEquals(Map.of(
                "n", Value.newBuilder().setIntegerValue(2).build(),
                "total", Value.newBuilder().setIntegerValue(30).build(),
                "mean", Value.newBuilder().setDoubleValue(15.0).build(),
                "property_1", Value.newBuilder().setIntegerValue(30).build(),
                "property_2", Value.newBuilder().setDoubleValue(15.0).build()), result);
    }

    private static <Req extends Message, Resp extends Message> MethodDescriptor<Req, Resp> method(
            String name, Req request, Resp response) {
        return MethodDescriptor.<Req, Resp>newBuilder()
                .setType(MethodDescriptor.MethodType.UNARY)
                .setFullMethodName("google.datastore.v1.Datastore/" + name)
                .setRequestMarshaller(ProtoUtils.marshaller(request))
                .setResponseMarshaller(ProtoUtils.marshaller(response))
                .build();
    }
}
