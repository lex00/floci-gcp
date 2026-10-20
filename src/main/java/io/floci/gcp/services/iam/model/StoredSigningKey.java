package io.floci.gcp.services.iam.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * The Google-managed key a service account signs with for {@code signBlob} and {@code signJwt}. Kept
 * apart from user-managed keys, so it is not returned by {@code serviceAccounts.keys.list}.
 */
@RegisterForReflection
public class StoredSigningKey {

    private String keyId;
    /** Base64 of the PKCS#8-encoded RSA private key. */
    private String privateKey;

    public StoredSigningKey() {}

    public StoredSigningKey(String keyId, String privateKey) {
        this.keyId = keyId;
        this.privateKey = privateKey;
    }

    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
}
