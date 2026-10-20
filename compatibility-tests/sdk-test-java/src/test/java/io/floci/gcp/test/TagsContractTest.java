package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.rpc.ApiException;
import com.google.cloud.resourcemanager.v3.TagBinding;
import com.google.cloud.resourcemanager.v3.TagBindingsClient;
import com.google.cloud.resourcemanager.v3.TagBindingsSettings;
import com.google.cloud.resourcemanager.v3.TagKey;
import com.google.cloud.resourcemanager.v3.TagKeysClient;
import com.google.cloud.resourcemanager.v3.TagKeysSettings;
import com.google.cloud.resourcemanager.v3.TagValue;
import com.google.cloud.resourcemanager.v3.TagValuesClient;
import com.google.cloud.resourcemanager.v3.TagValuesSettings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;

class TagsContractTest {
    @Test void keyValueAndProjectBindingRoundTrip() throws Exception {
        String project = TestFixtures.uniqueName("java-tags");
        NoCredentialsProvider auth = NoCredentialsProvider.create();
        try (TagKeysClient keys = TagKeysClient.create(TagKeysSettings.newHttpJsonBuilder().setEndpoint(TestFixtures.endpoint()).setCredentialsProvider(auth).build());
             TagValuesClient values = TagValuesClient.create(TagValuesSettings.newHttpJsonBuilder().setEndpoint(TestFixtures.endpoint()).setCredentialsProvider(auth).build());
             TagBindingsClient bindings = TagBindingsClient.create(TagBindingsSettings.newHttpJsonBuilder().setEndpoint(TestFixtures.endpoint()).setCredentialsProvider(auth).build())) {
            TagKey key = keys.createTagKeyAsync(TagKey.newBuilder().setParent("projects/" + project).setShortName("env").build())
                    .get(20, TimeUnit.SECONDS);
            assertThat(key.getNamespacedName()).isEqualTo(project + "/env");
            assertThat(keys.getNamespacedTagKey(project + "/env").getName()).isEqualTo(key.getName());

            TagValue value = values.createTagValueAsync(TagValue.newBuilder().setParent(key.getName()).setShortName("prod").build())
                    .get(20, TimeUnit.SECONDS);
            assertThat(value.getNamespacedName()).isEqualTo(project + "/env/prod");

            String parent = "//cloudresourcemanager.googleapis.com/projects/" + project;
            TagBinding binding = bindings.createTagBindingAsync(TagBinding.newBuilder().setParent(parent).setTagValue(value.getName()).build())
                    .get(20, TimeUnit.SECONDS);
            List<TagBinding> listed = StreamSupport.stream(bindings.listTagBindings(parent).iterateAll().spliterator(), false).toList();
            assertThat(listed).extracting(TagBinding::getName).containsExactly(binding.getName());
            assertThat(listed.get(0).getTagValueNamespacedName()).isEqualTo(project + "/env/prod");

            assertThatThrownBy(() -> values.deleteTagValueAsync(value.getName()).get(20, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ApiException.class)
                    .hasMessageContaining("has TagBindings");
            bindings.deleteTagBindingAsync(binding.getName()).get(20, TimeUnit.SECONDS);
            values.deleteTagValueAsync(value.getName()).get(20, TimeUnit.SECONDS);
            keys.deleteTagKeyAsync(key.getName()).get(20, TimeUnit.SECONDS);
            assertThat(keys.listTagKeys("projects/" + project).iterateAll()).isEmpty();
        }
    }
}
