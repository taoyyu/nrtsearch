/*
 * Copyright 2022 Yelp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.yelp.nrtsearch.server.state.backend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.protobuf.BoolValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.StringValue;
import com.yelp.nrtsearch.server.config.NrtsearchConfig;
import com.yelp.nrtsearch.server.grpc.GlobalStateInfo;
import com.yelp.nrtsearch.server.grpc.IndexGlobalState;
import com.yelp.nrtsearch.server.grpc.IndexLiveSettings;
import com.yelp.nrtsearch.server.grpc.IndexSettings;
import com.yelp.nrtsearch.server.grpc.IndexStateInfo;
import com.yelp.nrtsearch.server.grpc.SortFields;
import com.yelp.nrtsearch.server.grpc.SortType;
import com.yelp.nrtsearch.server.remote.RemoteBackend;
import com.yelp.nrtsearch.server.remote.s3.S3Backend;
import com.yelp.nrtsearch.server.state.BackendGlobalState;
import com.yelp.nrtsearch.server.state.GlobalState;
import com.yelp.nrtsearch.server.state.StateUtils;
import com.yelp.nrtsearch.test_utils.AmazonS3Provider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class RemoteStateBackendTest {
  @Rule public final TemporaryFolder folder = new TemporaryFolder();
  @Rule public final AmazonS3Provider s3Provider = new AmazonS3Provider(TEST_BUCKET);

  private static final String TEST_BUCKET = "remote-state-test";
  private static final String TEST_SERVICE_NAME = "test-service-name";
  private RemoteBackend remoteBackend;

  @Before
  public void setup() throws IOException {
    remoteBackend = new S3Backend(TEST_BUCKET, false, s3Provider.getAmazonS3());
  }

  private NrtsearchConfig getConfig(boolean readOnly) throws IOException {
    String configFile =
        String.join(
            "\n",
            "stateConfig:",
            "  backendType: REMOTE",
            "  remote:",
            "    readOnly: " + readOnly,
            "stateDir: " + folder.getRoot().getAbsolutePath(),
            "serviceName: " + TEST_SERVICE_NAME);
    return new NrtsearchConfig(new ByteArrayInputStream(configFile.getBytes()));
  }

  private GlobalState getMockGlobalState(boolean readOnly) throws IOException {
    GlobalState mockState = mock(GlobalState.class);
    NrtsearchConfig serverConfiguration = getConfig(readOnly);
    when(mockState.getConfiguration()).thenReturn(serverConfiguration);
    when(mockState.getStateDir()).thenReturn(Paths.get(serverConfiguration.getStateDir()));
    when(mockState.getRemoteBackend()).thenReturn(remoteBackend);
    return mockState;
  }

  private Path getLocalStateFilePath() {
    return Paths.get(
        folder.getRoot().getAbsolutePath(),
        StateUtils.GLOBAL_STATE_FOLDER,
        StateUtils.GLOBAL_STATE_FILE);
  }

  private Path getLocalIndexStateFilePath(String indexIdentifier) {
    return Paths.get(
        folder.getRoot().getAbsolutePath(), indexIdentifier, StateUtils.INDEX_STATE_FILE);
  }

  private GlobalStateInfo getS3State() throws IOException {
    if (!remoteBackend.exists(TEST_SERVICE_NAME, RemoteBackend.GlobalResourceType.GLOBAL_STATE)) {
      return null;
    }
    InputStream stateStream = remoteBackend.downloadGlobalState(TEST_SERVICE_NAME);
    byte[] stateBytes = stateStream.readAllBytes();
    return StateUtils.globalStateFromUTF8(stateBytes);
  }

  private IndexStateInfo getS3IndexState(String indexIdentifier) throws IOException {
    if (!remoteBackend.exists(
        TEST_SERVICE_NAME, indexIdentifier, RemoteBackend.IndexResourceType.INDEX_STATE)) {
      return null;
    }
    InputStream stateStream = remoteBackend.downloadIndexState(TEST_SERVICE_NAME, indexIdentifier);
    byte[] stateBytes = stateStream.readAllBytes();
    return StateUtils.indexStateFromUTF8(stateBytes);
  }

  private void writeStateToS3(GlobalStateInfo state) throws IOException {
    byte[] stateBytes = StateUtils.globalStateToUTF8(state);
    remoteBackend.uploadGlobalState(TEST_SERVICE_NAME, stateBytes);
  }

  private void writeIndexStateToS3(String indexIdentifier, IndexStateInfo state)
      throws IOException {
    byte[] stateBytes = StateUtils.indexStateToUTF8(state);
    remoteBackend.uploadIndexState(TEST_SERVICE_NAME, indexIdentifier, stateBytes);
  }

  @Test
  public void testCreatesLocalStateDir() throws IOException {
    Path stateDir = Paths.get(folder.getRoot().getAbsolutePath(), StateUtils.GLOBAL_STATE_FOLDER);
    assertFalse(stateDir.toFile().exists());

    new RemoteStateBackend(getMockGlobalState(false));
    assertTrue(stateDir.toFile().exists());
    assertTrue(stateDir.toFile().isDirectory());
  }

  @Test
  public void testCreatesDefaultState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(false));
    Path localFilePath = getLocalStateFilePath();
    assertFalse(localFilePath.toFile().exists());
    assertNull(getS3State());

    GlobalStateInfo globalState = stateBackend.loadOrCreateGlobalState();
    assertEquals(globalState, GlobalStateInfo.newBuilder().build());

    assertTrue(localFilePath.toFile().exists());
    assertTrue(localFilePath.toFile().isFile());

    GlobalStateInfo loadedLocalState = StateUtils.readStateFromFile(localFilePath);
    assertEquals(globalState, loadedLocalState);

    GlobalStateInfo stateFromTar = getS3State();
    assertNotNull(stateFromTar);
    assertEquals(globalState, stateFromTar);
  }

  @Test
  public void testLoadsSavedState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(false));
    Path localFilePath = getLocalStateFilePath();
    assertFalse(localFilePath.toFile().exists());

    GlobalStateInfo initialState =
        GlobalStateInfo.newBuilder()
            .setGen(25)
            .putIndices(
                "test_index",
                IndexGlobalState.newBuilder().setId("test_id_1").setStarted(false).build())
            .putIndices(
                "test_index_2",
                IndexGlobalState.newBuilder().setId("test_id_2").setStarted(true).build())
            .build();

    writeStateToS3(initialState);

    GlobalStateInfo loadedState = stateBackend.loadOrCreateGlobalState();
    assertEquals(initialState, loadedState);

    assertTrue(localFilePath.toFile().exists());
    assertTrue(localFilePath.toFile().isFile());

    GlobalStateInfo loadedLocalState = StateUtils.readStateFromFile(localFilePath);
    assertEquals(initialState, loadedLocalState);
  }

  @Test
  public void testCommitGlobalState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(false));
    Path localFilePath = getLocalStateFilePath();
    GlobalStateInfo initialState = stateBackend.loadOrCreateGlobalState();

    GlobalStateInfo updatedState =
        GlobalStateInfo.newBuilder()
            .setGen(26)
            .putIndices(
                "test_index",
                IndexGlobalState.newBuilder().setId("test_id_1").setStarted(true).build())
            .putIndices(
                "test_index_2",
                IndexGlobalState.newBuilder().setId("test_id_2").setStarted(false).build())
            .build();
    assertNotEquals(initialState, updatedState);

    stateBackend.commitGlobalState(updatedState);
    GlobalStateInfo loadedState = getS3State();
    assertEquals(updatedState, loadedState);
    GlobalStateInfo loadedLocalState = StateUtils.readStateFromFile(localFilePath);
    assertEquals(updatedState, loadedLocalState);

    GlobalStateInfo updatedState2 =
        GlobalStateInfo.newBuilder()
            .setGen(27)
            .putIndices(
                "test_index_3",
                IndexGlobalState.newBuilder().setId("test_id_3").setStarted(true).build())
            .build();
    assertNotEquals(updatedState, updatedState2);
    stateBackend.commitGlobalState(updatedState2);

    loadedState = getS3State();
    assertEquals(updatedState2, loadedState);
    loadedLocalState = StateUtils.readStateFromFile(localFilePath);
    assertEquals(updatedState2, loadedLocalState);
  }

  @Test(expected = NullPointerException.class)
  public void testCommitNullState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(false));
    stateBackend.loadOrCreateGlobalState();
    stateBackend.commitGlobalState(null);
  }

  @Test
  public void testReadOnlyNoInitialState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(true));
    assertNull(getS3State());
    try {
      stateBackend.loadOrCreateGlobalState();
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Cannot update remote state when configured as read only", e.getMessage());
    }
  }

  @Test
  public void testReadOnlyWithInitialState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(true));

    GlobalStateInfo initialState =
        GlobalStateInfo.newBuilder()
            .setGen(30)
            .putIndices(
                "test_index",
                IndexGlobalState.newBuilder().setId("test_id_1").setStarted(true).build())
            .putIndices(
                "test_index_2",
                IndexGlobalState.newBuilder().setId("test_id_2").setStarted(false).build())
            .build();

    writeStateToS3(initialState);
    GlobalStateInfo loadedState = stateBackend.loadOrCreateGlobalState();
    assertEquals(initialState, loadedState);
  }

  @Test
  public void testReadOnlyCommit() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(true));

    GlobalStateInfo initialState =
        GlobalStateInfo.newBuilder()
            .setGen(30)
            .putIndices(
                "test_index",
                IndexGlobalState.newBuilder().setId("test_id_1").setStarted(true).build())
            .putIndices(
                "test_index_2",
                IndexGlobalState.newBuilder().setId("test_id_2").setStarted(true).build())
            .build();

    writeStateToS3(initialState);
    GlobalStateInfo loadedState = stateBackend.loadOrCreateGlobalState();
    assertEquals(initialState, loadedState);

    GlobalStateInfo updatedState =
        GlobalStateInfo.newBuilder()
            .setGen(31)
            .putIndices(
                "test_index_3",
                IndexGlobalState.newBuilder().setId("test_id_3").setStarted(false).build())
            .putIndices(
                "test_index_4",
                IndexGlobalState.newBuilder().setId("test_id_4").setStarted(true).build())
            .putIndices(
                "test_index_5",
                IndexGlobalState.newBuilder().setId("test_id_5").setStarted(false).build())
            .build();

    try {
      stateBackend.commitGlobalState(updatedState);
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Cannot update remote state when configured as read only", e.getMessage());
    }
  }

  @Test
  public void testIndexStateNotExist() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(false));
    String indexIdentifier =
        BackendGlobalState.getUniqueIndexName("test_index", UUID.randomUUID().toString());
    assertNull(stateBackend.loadIndexState(indexIdentifier));
  }

  @Test
  public void testLoadsSavedIndexState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(false));
    String indexIdentifier =
        BackendGlobalState.getUniqueIndexName("test_index", UUID.randomUUID().toString());
    Path localFilePath = getLocalIndexStateFilePath(indexIdentifier);
    assertFalse(localFilePath.toFile().exists());

    IndexStateInfo initialState =
        IndexStateInfo.newBuilder()
            .setIndexName("test_index")
            .setGen(5)
            .setCommitted(true)
            .setSettings(
                IndexSettings.newBuilder()
                    .setConcurrentMergeSchedulerMaxThreadCount(
                        Int32Value.newBuilder().setValue(10).build())
                    .setDirectory(StringValue.newBuilder().setValue("MMapDirectory").build())
                    .setIndexMergeSchedulerAutoThrottle(
                        BoolValue.newBuilder().setValue(true).build())
                    .setIndexSort(
                        SortFields.newBuilder()
                            .addSortedFields(
                                SortType.newBuilder()
                                    .setFieldName("field1")
                                    .setReverse(true)
                                    .build())
                            .addSortedFields(SortType.newBuilder().setFieldName("field2").build())
                            .build())
                    .build())
            .setLiveSettings(
                IndexLiveSettings.newBuilder()
                    .setDefaultTerminateAfter(Int32Value.newBuilder().setValue(100).build())
                    .setIndexRamBufferSizeMB(DoubleValue.newBuilder().setValue(300.0).build())
                    .setMaxRefreshSec(DoubleValue.newBuilder().setValue(100.0).build())
                    .build())
            .build();

    writeIndexStateToS3(indexIdentifier, initialState);

    IndexStateInfo loadedState = stateBackend.loadIndexState(indexIdentifier);
    assertEquals(initialState, loadedState);

    assertTrue(localFilePath.toFile().exists());
    assertTrue(localFilePath.toFile().isFile());

    IndexStateInfo loadedLocalState = StateUtils.readIndexStateFromFile(localFilePath);
    assertEquals(initialState, loadedLocalState);
  }

  @Test
  public void testCommitIndexState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(false));
    String indexIdentifier =
        BackendGlobalState.getUniqueIndexName("test_index", UUID.randomUUID().toString());
    Path localFilePath = getLocalIndexStateFilePath(indexIdentifier);
    assertNull(stateBackend.loadIndexState(indexIdentifier));

    IndexStateInfo updatedState =
        IndexStateInfo.newBuilder()
            .setIndexName("test_index_2")
            .setGen(5)
            .setCommitted(true)
            .setSettings(
                IndexSettings.newBuilder()
                    .setConcurrentMergeSchedulerMaxThreadCount(
                        Int32Value.newBuilder().setValue(15).build())
                    .setDirectory(StringValue.newBuilder().setValue("FSDirectory").build())
                    .setIndexMergeSchedulerAutoThrottle(
                        BoolValue.newBuilder().setValue(false).build())
                    .setIndexSort(
                        SortFields.newBuilder()
                            .addSortedFields(
                                SortType.newBuilder()
                                    .setFieldName("field1")
                                    .setReverse(false)
                                    .build())
                            .addSortedFields(SortType.newBuilder().setFieldName("field2").build())
                            .build())
                    .build())
            .setLiveSettings(
                IndexLiveSettings.newBuilder()
                    .setDefaultTerminateAfter(Int32Value.newBuilder().setValue(200).build())
                    .setIndexRamBufferSizeMB(DoubleValue.newBuilder().setValue(100.0).build())
                    .setMaxRefreshSec(DoubleValue.newBuilder().setValue(50.0).build())
                    .build())
            .build();

    stateBackend.commitIndexState(indexIdentifier, updatedState);
    IndexStateInfo loadedState = getS3IndexState(indexIdentifier);
    assertEquals(updatedState, loadedState);
    IndexStateInfo loadedLocalState = StateUtils.readIndexStateFromFile(localFilePath);
    assertEquals(updatedState, loadedLocalState);

    IndexStateInfo updatedState2 =
        IndexStateInfo.newBuilder()
            .setIndexName("test_index_2")
            .setGen(6)
            .setCommitted(true)
            .setSettings(
                IndexSettings.newBuilder()
                    .setConcurrentMergeSchedulerMaxThreadCount(
                        Int32Value.newBuilder().setValue(16).build())
                    .setDirectory(StringValue.newBuilder().setValue("MMapDirectory").build())
                    .setIndexMergeSchedulerAutoThrottle(
                        BoolValue.newBuilder().setValue(true).build())
                    .setIndexSort(
                        SortFields.newBuilder()
                            .addSortedFields(
                                SortType.newBuilder()
                                    .setFieldName("field2")
                                    .setReverse(true)
                                    .build())
                            .build())
                    .build())
            .setLiveSettings(
                IndexLiveSettings.newBuilder()
                    .setDefaultTerminateAfter(Int32Value.newBuilder().setValue(300).build())
                    .setIndexRamBufferSizeMB(DoubleValue.newBuilder().setValue(200.0).build())
                    .setMaxRefreshSec(DoubleValue.newBuilder().setValue(75.0).build())
                    .build())
            .build();
    assertNotEquals(updatedState, updatedState2);
    stateBackend.commitIndexState(indexIdentifier, updatedState2);

    loadedState = getS3IndexState(indexIdentifier);
    assertEquals(updatedState2, loadedState);
    loadedLocalState = StateUtils.readIndexStateFromFile(localFilePath);
    assertEquals(updatedState2, loadedLocalState);
  }

  @Test(expected = NullPointerException.class)
  public void testLoadNullIndexState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(false));
    stateBackend.loadIndexState(null);
  }

  @Test
  public void testCommitNullIndexState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(false));
    String indexIdentifier =
        BackendGlobalState.getUniqueIndexName("test_index", UUID.randomUUID().toString());
    stateBackend.loadIndexState(indexIdentifier);
    try {
      stateBackend.commitIndexState(indexIdentifier, null);
      fail();
    } catch (NullPointerException ignore) {

    }

    try {
      stateBackend.commitIndexState(null, IndexStateInfo.newBuilder().build());
      fail();
    } catch (NullPointerException ignore) {

    }
  }

  @Test
  public void testReadOnlyNoInitialIndexState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(true));
    String indexIdentifier =
        BackendGlobalState.getUniqueIndexName("test_index", UUID.randomUUID().toString());
    assertNull(getS3IndexState(indexIdentifier));
    assertNull(stateBackend.loadIndexState(indexIdentifier));
  }

  @Test
  public void testReadOnlyWithInitialIndexState() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(true));
    String indexIdentifier =
        BackendGlobalState.getUniqueIndexName("test_index", UUID.randomUUID().toString());

    IndexStateInfo initialState =
        IndexStateInfo.newBuilder()
            .setIndexName("test_index")
            .setGen(5)
            .setCommitted(true)
            .setSettings(
                IndexSettings.newBuilder()
                    .setConcurrentMergeSchedulerMaxThreadCount(
                        Int32Value.newBuilder().setValue(10).build())
                    .setDirectory(StringValue.newBuilder().setValue("MMapDirectory").build())
                    .setIndexMergeSchedulerAutoThrottle(
                        BoolValue.newBuilder().setValue(true).build())
                    .setIndexSort(
                        SortFields.newBuilder()
                            .addSortedFields(
                                SortType.newBuilder()
                                    .setFieldName("field1")
                                    .setReverse(true)
                                    .build())
                            .addSortedFields(SortType.newBuilder().setFieldName("field2").build())
                            .build())
                    .build())
            .setLiveSettings(
                IndexLiveSettings.newBuilder()
                    .setDefaultTerminateAfter(Int32Value.newBuilder().setValue(100).build())
                    .setIndexRamBufferSizeMB(DoubleValue.newBuilder().setValue(300.0).build())
                    .setMaxRefreshSec(DoubleValue.newBuilder().setValue(100.0).build())
                    .build())
            .build();

    writeIndexStateToS3(indexIdentifier, initialState);
    IndexStateInfo loadedState = stateBackend.loadIndexState(indexIdentifier);
    assertEquals(initialState, loadedState);
  }

  @Test
  public void testReadOnlyIndexCommit() throws IOException {
    StateBackend stateBackend = new RemoteStateBackend(getMockGlobalState(true));
    String indexIdentifier =
        BackendGlobalState.getUniqueIndexName("test_index", UUID.randomUUID().toString());

    IndexStateInfo initialState =
        IndexStateInfo.newBuilder()
            .setIndexName("test_index_2")
            .setGen(5)
            .setCommitted(true)
            .setSettings(
                IndexSettings.newBuilder()
                    .setConcurrentMergeSchedulerMaxThreadCount(
                        Int32Value.newBuilder().setValue(15).build())
                    .setDirectory(StringValue.newBuilder().setValue("FSDirectory").build())
                    .setIndexMergeSchedulerAutoThrottle(
                        BoolValue.newBuilder().setValue(false).build())
                    .setIndexSort(
                        SortFields.newBuilder()
                            .addSortedFields(
                                SortType.newBuilder()
                                    .setFieldName("field1")
                                    .setReverse(false)
                                    .build())
                            .addSortedFields(SortType.newBuilder().setFieldName("field2").build())
                            .build())
                    .build())
            .setLiveSettings(
                IndexLiveSettings.newBuilder()
                    .setDefaultTerminateAfter(Int32Value.newBuilder().setValue(200).build())
                    .setIndexRamBufferSizeMB(DoubleValue.newBuilder().setValue(100.0).build())
                    .setMaxRefreshSec(DoubleValue.newBuilder().setValue(50.0).build())
                    .build())
            .build();

    writeIndexStateToS3(indexIdentifier, initialState);
    IndexStateInfo loadedState = stateBackend.loadIndexState(indexIdentifier);
    assertEquals(initialState, loadedState);

    IndexStateInfo updatedState =
        IndexStateInfo.newBuilder()
            .setIndexName("test_index_2")
            .setGen(6)
            .setCommitted(true)
            .setSettings(
                IndexSettings.newBuilder()
                    .setConcurrentMergeSchedulerMaxThreadCount(
                        Int32Value.newBuilder().setValue(16).build())
                    .setDirectory(StringValue.newBuilder().setValue("MMapDirectory").build())
                    .setIndexMergeSchedulerAutoThrottle(
                        BoolValue.newBuilder().setValue(true).build())
                    .setIndexSort(
                        SortFields.newBuilder()
                            .addSortedFields(
                                SortType.newBuilder()
                                    .setFieldName("field2")
                                    .setReverse(true)
                                    .build())
                            .build())
                    .build())
            .setLiveSettings(
                IndexLiveSettings.newBuilder()
                    .setDefaultTerminateAfter(Int32Value.newBuilder().setValue(300).build())
                    .setIndexRamBufferSizeMB(DoubleValue.newBuilder().setValue(200.0).build())
                    .setMaxRefreshSec(DoubleValue.newBuilder().setValue(75.0).build())
                    .build())
            .build();

    try {
      stateBackend.commitIndexState(indexIdentifier, updatedState);
      fail();
    } catch (IllegalStateException e) {
      assertEquals("Cannot update remote state when configured as read only", e.getMessage());
    }
  }
}
