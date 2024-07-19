/*
 * Copyright 2024 Yelp Inc.
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
package com.yelp.nrtsearch.server.grpc;

import com.yelp.nrtsearch.server.config.ThreadPoolConfiguration;
import com.yelp.nrtsearch.server.utils.ThreadPoolExecutorFactory;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallExecutorSupplier;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import javax.annotation.Nullable;

/**
 * GrpcServerExecutorSupplier provides the thread pool executors for the gRPC server. It provides
 * LuceneServer executor for all methods except metrics, for which it provides a separate executor
 * so that even if all LuceneServer threads are occupied by search/index requests, metrics requests
 * will still keep working. The {@link ServerCallExecutorSupplier} is an experimental API, see these
 * links for more details:
 *
 * <ul>
 *   <li><a href="https://github.com/grpc/grpc-java/issues/7874">grpc-java#7874</a>
 *   <li><a href="https://github.com/grpc/grpc-java/issues/8274">grpc-java#8274</a>
 *   <li><a href="https://github.com/grpc/grpc-java/pull/8266">grpc-java#8266</a>
 * </ul>
 */
public class GrpcServerExecutorSupplier implements ServerCallExecutorSupplier {

  private final ThreadPoolExecutor luceneServerThreadPoolExecutor;
  private final ThreadPoolExecutor metricsThreadPoolExecutor;
  private final ThreadPoolExecutor grpcThreadPoolExecutor;

  public GrpcServerExecutorSupplier(ThreadPoolConfiguration threadPoolConfiguration) {
    luceneServerThreadPoolExecutor =
        ThreadPoolExecutorFactory.getThreadPoolExecutor(
            ThreadPoolExecutorFactory.ExecutorType.LUCENESERVER, threadPoolConfiguration);
    metricsThreadPoolExecutor =
        ThreadPoolExecutorFactory.getThreadPoolExecutor(
            ThreadPoolExecutorFactory.ExecutorType.METRICS, threadPoolConfiguration);
    grpcThreadPoolExecutor =
        ThreadPoolExecutorFactory.getThreadPoolExecutor(
            ThreadPoolExecutorFactory.ExecutorType.GRPC, threadPoolConfiguration);
  }

  public ThreadPoolExecutor getLuceneServerThreadPoolExecutor() {
    return luceneServerThreadPoolExecutor;
  }

  public ThreadPoolExecutor getMetricsThreadPoolExecutor() {
    return metricsThreadPoolExecutor;
  }

  public ThreadPoolExecutor getGrpcThreadPoolExecutor() {
    return grpcThreadPoolExecutor;
  }

  @Nullable
  @Override
  public <ReqT, RespT> Executor getExecutor(ServerCall<ReqT, RespT> serverCall, Metadata metadata) {
    if ("metrics".equals(serverCall.getMethodDescriptor().getBareMethodName())) {
      return metricsThreadPoolExecutor;
    }
    return luceneServerThreadPoolExecutor;
  }
}