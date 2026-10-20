package io.floci.gcp.services.cloudkms;

import com.google.cloud.kms.v1.AsymmetricSignRequest;
import com.google.cloud.kms.v1.CreateCryptoKeyRequest;
import com.google.cloud.kms.v1.CreateKeyRingRequest;
import com.google.cloud.kms.v1.CryptoKey;
import com.google.cloud.kms.v1.CryptoKeyVersion;
import com.google.cloud.kms.v1.CryptoKeyVersionTemplate;
import com.google.cloud.kms.v1.DecryptRequest;
import com.google.cloud.kms.v1.Digest;
import com.google.cloud.kms.v1.EncryptRequest;
import com.google.cloud.kms.v1.KeyManagementServiceGrpc;
import com.google.cloud.kms.v1.MacSignRequest;
import com.google.cloud.kms.v1.MacSignResponse;
import com.google.cloud.kms.v1.MacVerifyRequest;
import com.google.cloud.kms.v1.MacVerifyResponse;
import com.google.cloud.kms.v1.ProtectionLevel;
import com.google.protobuf.ByteString;
import com.google.protobuf.Int64Value;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32C;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cloud KMS over gRPC on the shared port. */
@QuarkusTest
class CloudKmsGrpcIntegrationTest {

    private static final String LOCATION = "projects/kms-crc-grpc-it/locations/global";

    @TestHTTPResource
    URI endpoint;

    private ManagedChannel channel;
    private KeyManagementServiceGrpc.KeyManagementServiceBlockingStub kms;

    @BeforeEach
    void connect() {
        channel = ManagedChannelBuilder.forAddress(endpoint.getHost(), endpoint.getPort()).usePlaintext().build();
        kms = KeyManagementServiceGrpc.newBlockingStub(channel);
    }

    @AfterEach
    void close() throws InterruptedException {
        channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private static long crc32c(ByteString data) {
        CRC32C crc = new CRC32C();
        crc.update(data.toByteArray());
        return crc.getValue();
    }

    @Test
    void decryptVerifiesCiphertextAndAadChecksums() {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("ring").build());
        String key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/ring").setCryptoKeyId("decrypt-crc")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ENCRYPT_DECRYPT))
                .build()).getName();
        ByteString aad = ByteString.copyFrom("context", StandardCharsets.UTF_8);
        ByteString ciphertext = kms.encrypt(EncryptRequest.newBuilder().setName(key)
                .setPlaintext(ByteString.copyFrom("hello", StandardCharsets.UTF_8))
                .setAdditionalAuthenticatedData(aad).build()).getCiphertext();
        DecryptRequest decrypt = DecryptRequest.newBuilder().setName(key).setCiphertext(ciphertext)
                .setAdditionalAuthenticatedData(aad)
                .setCiphertextCrc32C(Int64Value.of(crc32c(ciphertext)))
                .setAdditionalAuthenticatedDataCrc32C(Int64Value.of(crc32c(aad))).build();

        assertEquals("hello", kms.decrypt(decrypt).getPlaintext().toStringUtf8());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.decrypt(decrypt.toBuilder().setCiphertextCrc32C(Int64Value.of(1)).build()))
                .getStatus().getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.decrypt(decrypt.toBuilder().setAdditionalAuthenticatedDataCrc32C(Int64Value.of(1)).build()))
                .getStatus().getCode());
    }

    @Test
    void asymmetricSignVerifiesTheDataChecksum() {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("sign-ring").build());
        String key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/sign-ring").setCryptoKeyId("sign-data")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)))
                .build()).getName();
        ByteString data = ByteString.copyFrom("payload", StandardCharsets.UTF_8);
        AsymmetricSignRequest sign = AsymmetricSignRequest.newBuilder().setName(key + "/cryptoKeyVersions/1")
                .setData(data).setDataCrc32C(Int64Value.of(crc32c(data))).build();

        assertTrue(kms.asymmetricSign(sign).getVerifiedDataCrc32C());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.asymmetricSign(sign.toBuilder().setDataCrc32C(Int64Value.of(1)).build()))
                .getStatus().getCode());
    }

    @Test
    void asymmetricSignRejectsDigestAndDataTogether() throws Exception {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("sign-both-ring").build());
        String key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/sign-both-ring").setCryptoKeyId("sign-both")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)))
                .build()).getName();
        ByteString data = ByteString.copyFrom("payload", StandardCharsets.UTF_8);
        ByteString sha256 = ByteString.copyFrom(MessageDigest.getInstance("SHA-256").digest(data.toByteArray()));
        AsymmetricSignRequest sign = AsymmetricSignRequest.newBuilder().setName(key + "/cryptoKeyVersions/1")
                .setDigest(Digest.newBuilder().setSha256(sha256)).setData(data).build();

        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.asymmetricSign(sign)).getStatus().getCode());
    }

    @Test
    void asymmetricSignRejectsSha384DigestAndDataTogether() throws Exception {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("sign-both-384-ring").build());
        String key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/sign-both-384-ring").setCryptoKeyId("sign-both-384")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.ASYMMETRIC_SIGN)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)))
                .build()).getName();
        ByteString data = ByteString.copyFrom("payload", StandardCharsets.UTF_8);
        ByteString sha384 = ByteString.copyFrom(MessageDigest.getInstance("SHA-384").digest(data.toByteArray()));
        AsymmetricSignRequest sign = AsymmetricSignRequest.newBuilder().setName(key + "/cryptoKeyVersions/1")
                .setDigest(Digest.newBuilder().setSha384(sha384)).setData(data).build();

        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.asymmetricSign(sign)).getStatus().getCode());
    }

    @Test
    void macSignAndMacVerifyRoundTripWithChecksums() {
        kms.createKeyRing(CreateKeyRingRequest.newBuilder().setParent(LOCATION).setKeyRingId("mac-ring").build());
        CryptoKey key = kms.createCryptoKey(CreateCryptoKeyRequest.newBuilder()
                .setParent(LOCATION + "/keyRings/mac-ring").setCryptoKeyId("mac")
                .setCryptoKey(CryptoKey.newBuilder().setPurpose(CryptoKey.CryptoKeyPurpose.MAC)
                        .setVersionTemplate(CryptoKeyVersionTemplate.newBuilder()
                                .setAlgorithm(CryptoKeyVersion.CryptoKeyVersionAlgorithm.HMAC_SHA256)))
                .build());
        assertEquals(CryptoKey.CryptoKeyPurpose.MAC, key.getPurpose());
        assertEquals(CryptoKeyVersion.CryptoKeyVersionAlgorithm.HMAC_SHA256, key.getVersionTemplate().getAlgorithm());
        String version = key.getName() + "/cryptoKeyVersions/1";
        ByteString data = ByteString.copyFrom("payload", StandardCharsets.UTF_8);

        MacSignResponse signed = kms.macSign(MacSignRequest.newBuilder().setName(version)
                .setData(data).setDataCrc32C(Int64Value.of(crc32c(data))).build());
        assertEquals(version, signed.getName());
        assertEquals(32, signed.getMac().size());
        assertEquals(crc32c(signed.getMac()), signed.getMacCrc32C().getValue());
        assertTrue(signed.getVerifiedDataCrc32C());
        assertEquals(ProtectionLevel.SOFTWARE, signed.getProtectionLevel());

        MacVerifyRequest verify = MacVerifyRequest.newBuilder().setName(version).setData(data)
                .setDataCrc32C(Int64Value.of(crc32c(data))).setMac(signed.getMac())
                .setMacCrc32C(Int64Value.of(crc32c(signed.getMac()))).build();
        MacVerifyResponse verified = kms.macVerify(verify);
        assertTrue(verified.getSuccess());
        assertTrue(verified.getVerifiedSuccessIntegrity());
        assertTrue(verified.getVerifiedDataCrc32C());
        assertTrue(verified.getVerifiedMacCrc32C());
        assertEquals(version, verified.getName());
        assertEquals(ProtectionLevel.SOFTWARE, verified.getProtectionLevel());

        ByteString otherData = ByteString.copyFrom("other", StandardCharsets.UTF_8);
        MacVerifyResponse rejected = kms.macVerify(MacVerifyRequest.newBuilder().setName(version)
                .setData(otherData).setMac(signed.getMac()).build());
        assertFalse(rejected.getSuccess());
        assertFalse(rejected.getVerifiedSuccessIntegrity());
        assertFalse(rejected.getVerifiedDataCrc32C());

        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.macSign(MacSignRequest.newBuilder().setName(version).setData(data)
                        .setDataCrc32C(Int64Value.of(1)).build())).getStatus().getCode());
        assertEquals(Status.Code.INVALID_ARGUMENT, assertThrows(StatusRuntimeException.class,
                () -> kms.macVerify(verify.toBuilder().setMacCrc32C(Int64Value.of(1)).build()))
                .getStatus().getCode());
    }
}
