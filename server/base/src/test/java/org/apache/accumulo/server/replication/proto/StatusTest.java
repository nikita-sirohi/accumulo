/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.server.replication.proto;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.apache.accumulo.server.replication.proto.Replication.Status;
import org.junit.jupiter.api.Test;

@Deprecated
public class StatusTest {

  @Test
  public void equality() {
    Status replicated = Status.newBuilder().setBegin(Long.MAX_VALUE).setEnd(0).setInfiniteEnd(true)
        .setClosed(false).build();
    Status unreplicated = Status.newBuilder().setBegin(0).setEnd(0).setInfiniteEnd(true)
        .setClosed(false).build();

    assertNotEquals(replicated, unreplicated);
  }

}
