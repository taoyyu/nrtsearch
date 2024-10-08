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
package com.yelp.nrtsearch.tools.nrt_utils.state;

import com.amazonaws.services.s3.AmazonS3;
import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.util.JsonFormat;
import com.yelp.nrtsearch.server.grpc.GlobalStateInfo;
import com.yelp.nrtsearch.server.grpc.IndexGlobalState;
import com.yelp.nrtsearch.server.remote.RemoteBackend;
import com.yelp.nrtsearch.server.remote.s3.S3Backend;
import com.yelp.nrtsearch.server.state.BackendGlobalState;
import com.yelp.nrtsearch.server.state.StateUtils;
import com.yelp.nrtsearch.server.utils.TimeStringUtils;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = UpdateGlobalIndexStateCommand.UPDATE_GLOBAL_INDEX_STATE,
    description = "Update index properties in global state")
public class UpdateGlobalIndexStateCommand implements Callable<Integer> {
  public static final String UPDATE_GLOBAL_INDEX_STATE = "updateGlobalIndexState";

  @CommandLine.Option(
      names = {"-s", "--serviceName"},
      description = "Name of nrtsearch cluster",
      required = true)
  private String serviceName;

  @CommandLine.Option(
      names = {"-i", "--indexName"},
      description = "Name of index to update",
      required = true)
  private String indexName;

  @CommandLine.Option(
      names = {"-b", "--bucketName"},
      description = "Name of bucket containing state files",
      required = true)
  private String bucketName;

  @CommandLine.Option(
      names = {"--region"},
      description = "AWS region name, such as us-west-1, us-west-2, us-east-1")
  private String region;

  @CommandLine.Option(
      names = {"-c", "--credsFile"},
      description =
          "File holding AWS credentials; Will use DefaultCredentialProvider if this is unset.")
  private String credsFile;

  @CommandLine.Option(
      names = {"-p", "--credsProfile"},
      description = "Profile to use from creds file; Neglected when credsFile is unset.",
      defaultValue = "default")
  private String credsProfile;

  @CommandLine.Option(
      names = {"--setId"},
      description =
          "If specified, update index id to this value. Should match format yyyyMMddHHmmssSSS")
  private String dateTimeString;

  @CommandLine.Option(
      names = {"--setStarted"},
      description =
          "Optionally update index started flag for index, valid values: 'true' or 'false'")
  private String started;

  @CommandLine.Option(
      names = {"--maxRetry"},
      description = "Maximum number of retry attempts for S3 failed requests",
      defaultValue = "20")
  private int maxRetry;

  private AmazonS3 s3Client;

  @VisibleForTesting
  void setS3Client(AmazonS3 s3Client) {
    this.s3Client = s3Client;
  }

  @VisibleForTesting
  static boolean validateParams(String started, String dateTimeString) {
    if (started != null) {
      if (!started.equalsIgnoreCase("true") && !started.equalsIgnoreCase("false")) {
        System.out.println("setStarted must be one of 'true' or 'false'");
        return false;
      }
    }
    if (dateTimeString != null) {
      if (!TimeStringUtils.isTimeStringMs(dateTimeString)) {
        System.out.println("Invalid date time format: " + dateTimeString);
        return false;
      }
    }
    return true;
  }

  @Override
  public Integer call() throws Exception {
    if (!validateParams(started, dateTimeString)) {
      return 1;
    }
    if (s3Client == null) {
      s3Client =
          StateCommandUtils.createS3Client(bucketName, region, credsFile, credsProfile, maxRetry);
    }
    S3Backend s3Backend = new S3Backend(bucketName, false, s3Client);

    String stateFileContents = StateCommandUtils.getGlobalStateFileContents(s3Backend, serviceName);
    if (stateFileContents == null) {
      System.out.println("Could not find cluster global state");
      return 1;
    }

    GlobalStateInfo.Builder builder = GlobalStateInfo.newBuilder();
    JsonFormat.parser().merge(stateFileContents, builder);
    GlobalStateInfo globalStateInfo = builder.build();
    System.out.println("Current global state: " + JsonFormat.printer().print(globalStateInfo));

    if (!globalStateInfo.containsIndices(indexName)) {
      System.out.println("Index does not exist in global state: " + indexName);
      return 1;
    }

    IndexGlobalState indexGlobalState = globalStateInfo.getIndicesOrThrow(indexName);
    boolean updated = false;

    if (dateTimeString != null) {
      String updatedIndexResource =
          BackendGlobalState.getUniqueIndexName(indexName, dateTimeString);
      boolean dataExists =
          s3Backend.exists(
              serviceName, updatedIndexResource, RemoteBackend.IndexResourceType.POINT_STATE);
      boolean stateExists =
          s3Backend.exists(
              serviceName, updatedIndexResource, RemoteBackend.IndexResourceType.INDEX_STATE);
      if (!dataExists || !stateExists) {
        System.out.println("Missing blessed resources for new index id: " + dateTimeString);
        System.out.println("Data resource: " + updatedIndexResource + ", exists: " + dataExists);
        System.out.println("State resource: " + updatedIndexResource + ", exists: " + stateExists);
        return 1;
      }

      indexGlobalState = indexGlobalState.toBuilder().setId(dateTimeString).build();
      updated = true;
    }

    if (started != null) {
      indexGlobalState =
          indexGlobalState.toBuilder().setStarted(Boolean.parseBoolean(started)).build();
      updated = true;
    }

    if (updated) {
      GlobalStateInfo.Builder newStateBuilder = globalStateInfo.toBuilder();
      newStateBuilder.setGen(globalStateInfo.getGen() + 1);
      newStateBuilder.putIndices(indexName, indexGlobalState);
      GlobalStateInfo updatedGlobalStateInfo = newStateBuilder.build();
      String stateStr = JsonFormat.printer().print(updatedGlobalStateInfo);
      byte[] stateBytes = StateUtils.toUTF8(stateStr);
      s3Backend.uploadGlobalState(serviceName, stateBytes);

      System.out.println("Updated global state: " + stateStr);
    } else {
      System.out.println("No update requested");
    }

    return 0;
  }
}
