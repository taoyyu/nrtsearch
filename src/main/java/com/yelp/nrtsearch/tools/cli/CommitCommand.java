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

import com.yelp.nrtsearch.server.grpc.NrtsearchClient;
import java.util.concurrent.Callable;
import picocli.CommandLine;

@CommandLine.Command(
    name = CommitCommand.COMMIT,
    description = "Commits all pending changes to durable storage for the given index")
public class CommitCommand implements Callable<Integer> {
  public static final String COMMIT = "commit";

  @CommandLine.ParentCommand private NrtsearchClientCommand baseCmd;

  @CommandLine.Option(
      names = {"-i", "--indexName"},
      description = "Name of the index to be committed",
      required = true)
  private String indexName;

  public String getIndexName() {
    return indexName;
  }

  @Override
  public Integer call() throws Exception {
    NrtsearchClient client = baseCmd.getClient();
    try {
      client.commit(getIndexName());
    } finally {
      client.shutdown();
    }
    return 0;
  }
}
