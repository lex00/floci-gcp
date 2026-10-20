package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;

@QuarkusTest
class ComputeIamDisabledIntegrationTest extends ComputeIamEnforcementContract {
    @Override
    boolean enforced() {
        return false;
    }
}
