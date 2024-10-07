/*
 * Copyright 2020 Yelp Inc.
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
package com.yelp.nrtsearch.tools.cli;

import static com.yelp.nrtsearch.tools.cli.NrtsearchClientCommand.logger;

import com.yelp.nrtsearch.server.grpc.NrtsearchClient;
import com.yelp.nrtsearch.server.grpc.ReplicationServerClient;
import com.yelp.nrtsearch.server.grpc.SearcherVersion;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = GetCurrentSearcherVersion.CURRENT_SEARCHER_VERSION,
    description =
        "Gets the most recent searcher version on replica, should match primary version returned on the last writeNRT call")
public class GetCurrentSearcherVersion implements Callable<Integer> {
  public static final String CURRENT_SEARCHER_VERSION = "currSearcherVer";

  @CommandLine.ParentCommand private NrtsearchClientCommand baseCmd;

  @CommandLine.Option(
      names = {"-i", "--indexName"},
      description = "Name of the index whose NRT point is to be updated",
      required = true)
  private String indexName;

  public String getIndexName() {
    return indexName;
  }

  @CommandLine.Option(
      names = {"--host"},
      description = "Replica host name (default: ${DEFAULT-VALUE})",
      defaultValue = "localhost")
  private String hostName;

  public String getHostName() {
    return hostName;
  }

  @CommandLine.Option(
      names = {"-p", "--port"},
      description = "Replica replication port number",
      required = true)
  private String port;

  public int getPort() {
    return Integer.parseInt(port);
  }

  @Override
  public Integer call() throws Exception {
    NrtsearchClient client = baseCmd.getClient();
    try {
      ReplicationServerClient replServerClient =
          new ReplicationServerClient(getHostName(), getPort());
      SearcherVersion searcherVersion = replServerClient.getCurrentSearcherVersion(getIndexName());
      logger.info("searcherVersion: " + searcherVersion.getVersion());
    } finally {
      client.shutdown();
    }
    return 0;
  }
}
