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
package org.apache.accumulo.test.replication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.TableId;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.replication.ReplicationSchema.StatusSection;
import org.apache.accumulo.core.replication.ReplicationSchema.WorkSection;
import org.apache.accumulo.core.replication.ReplicationTable;
import org.apache.accumulo.core.replication.ReplicationTarget;
import org.apache.accumulo.core.security.TablePermission;
import org.apache.accumulo.manager.replication.WorkMaker;
import org.apache.accumulo.server.replication.StatusUtil;
import org.apache.accumulo.server.replication.proto.Replication.Status;
import org.apache.accumulo.test.functional.ConfigurableMacBase;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.google.common.collect.Iterables;

@Disabled("Replication ITs are not stable and not currently maintained")
@Deprecated
public class WorkMakerIT extends ConfigurableMacBase {

  private AccumuloClient client;

  private static class MockWorkMaker extends WorkMaker {

    public MockWorkMaker(AccumuloClient client) {
      super(null, client);
    }

    @Override
    public void setBatchWriter(BatchWriter bw) {
      super.setBatchWriter(bw);
    }

    @Override
    public void addWorkRecord(Text file, Value v, Map<String,String> targets,
        TableId sourceTableId) {
      super.addWorkRecord(file, v, targets, sourceTableId);
    }

    @Override
    public boolean shouldCreateWork(Status status) {
      return super.shouldCreateWork(status);
    }

  }

  @BeforeEach
  public void setupInstance() throws Exception {
    client = Accumulo.newClient().from(getClientProperties()).build();
    ReplicationTable.setOnline(client);
    client.securityOperations().grantTablePermission(client.whoami(), ReplicationTable.NAME,
        TablePermission.WRITE);
    client.securityOperations().grantTablePermission(client.whoami(), ReplicationTable.NAME,
        TablePermission.READ);
  }

  @Test
  public void singleUnitSingleTarget() throws Exception {
    String table = testName();
    client.tableOperations().create(table);
    TableId tableId = TableId.of(client.tableOperations().tableIdMap().get(table));
    String file = "hdfs://localhost:8020/accumulo/wal/123456-1234-1234-12345678";

    // Create a status record for a file
    long timeCreated = System.currentTimeMillis();
    Mutation m = new Mutation(new Path(file).toString());
    m.put(StatusSection.NAME, new Text(tableId.canonical()),
        StatusUtil.fileCreatedValue(timeCreated));
    BatchWriter bw = ReplicationTable.getBatchWriter(client);
    bw.addMutation(m);
    bw.flush();

    // Assert that we have one record in the status section
    ReplicationTarget expected;
    try (Scanner s = ReplicationTable.getScanner(client)) {
      StatusSection.limit(s);
      assertEquals(1, Iterables.size(s));

      MockWorkMaker workMaker = new MockWorkMaker(client);

      // Invoke the addWorkRecord method to create a Work record from the Status record earlier
      expected = new ReplicationTarget("remote_cluster_1", "4", tableId);
      workMaker.setBatchWriter(bw);
      workMaker.addWorkRecord(new Text(file), StatusUtil.fileCreatedValue(timeCreated),
          Map.of("remote_cluster_1", "4"), tableId);
    }

    // Scan over just the WorkSection
    try (Scanner s = ReplicationTable.getScanner(client)) {
      WorkSection.limit(s);

      Entry<Key,Value> workEntry = Iterables.getOnlyElement(s);
      Key workKey = workEntry.getKey();
      ReplicationTarget actual = ReplicationTarget.from(workKey.getColumnQualifier());

      assertEquals(file, workKey.getRow().toString());
      assertEquals(WorkSection.NAME, workKey.getColumnFamily());
      assertEquals(expected, actual);
      assertEquals(workEntry.getValue(), StatusUtil.fileCreatedValue(timeCreated));
    }
  }

  @Test
  public void singleUnitMultipleTargets() throws Exception {
    String table = testName();
    client.tableOperations().create(table);

    TableId tableId = TableId.of(client.tableOperations().tableIdMap().get(table));

    String file = "hdfs://localhost:8020/accumulo/wal/123456-1234-1234-12345678";

    Mutation m = new Mutation(new Path(file).toString());
    m.put(StatusSection.NAME, new Text(tableId.canonical()),
        StatusUtil.fileCreatedValue(System.currentTimeMillis()));
    BatchWriter bw = ReplicationTable.getBatchWriter(client);
    bw.addMutation(m);
    bw.flush();

    // Assert that we have one record in the status section
    Set<ReplicationTarget> expectedTargets = new HashSet<>();
    try (Scanner s = ReplicationTable.getScanner(client)) {
      StatusSection.limit(s);
      assertEquals(1, Iterables.size(s));

      MockWorkMaker workMaker = new MockWorkMaker(client);

      Map<String,String> targetClusters =
          Map.of("remote_cluster_1", "4", "remote_cluster_2", "6", "remote_cluster_3", "8");

      for (Entry<String,String> cluster : targetClusters.entrySet()) {
        expectedTargets.add(new ReplicationTarget(cluster.getKey(), cluster.getValue(), tableId));
      }
      workMaker.setBatchWriter(bw);
      workMaker.addWorkRecord(new Text(file),
          StatusUtil.fileCreatedValue(System.currentTimeMillis()), targetClusters, tableId);
    }

    try (Scanner s = ReplicationTable.getScanner(client)) {
      WorkSection.limit(s);

      Set<ReplicationTarget> actualTargets = new HashSet<>();
      for (Entry<Key,Value> entry : s) {
        assertEquals(file, entry.getKey().getRow().toString());
        assertEquals(WorkSection.NAME, entry.getKey().getColumnFamily());

        ReplicationTarget target = ReplicationTarget.from(entry.getKey().getColumnQualifier());
        actualTargets.add(target);
      }

      for (ReplicationTarget expected : expectedTargets) {
        assertTrue(actualTargets.contains(expected), "Did not find expected target: " + expected);
        actualTargets.remove(expected);
      }

      assertTrue(actualTargets.isEmpty(), "Found extra replication work entries: " + actualTargets);
    }
  }

  @Test
  public void dontCreateWorkForEntriesWithNothingToReplicate() throws Exception {
    String table = testName();
    client.tableOperations().create(table);
    String tableId = client.tableOperations().tableIdMap().get(table);
    String file = "hdfs://localhost:8020/accumulo/wal/123456-1234-1234-12345678";

    Mutation m = new Mutation(new Path(file).toString());
    m.put(StatusSection.NAME, new Text(tableId),
        StatusUtil.fileCreatedValue(System.currentTimeMillis()));
    BatchWriter bw = ReplicationTable.getBatchWriter(client);
    bw.addMutation(m);
    bw.flush();

    // Assert that we have one record in the status section
    try (Scanner s = ReplicationTable.getScanner(client)) {
      StatusSection.limit(s);
      assertEquals(1, Iterables.size(s));

      MockWorkMaker workMaker = new MockWorkMaker(client);

      client.tableOperations().setProperty(ReplicationTable.NAME,
          Property.TABLE_REPLICATION_TARGET.getKey() + "remote_cluster_1", "4");

      workMaker.setBatchWriter(bw);

      // If we don't shortcircuit out, we should get an exception because
      // ServerConfiguration.getTableConfiguration
      // won't work with MockAccumulo
      workMaker.run();
    }

    try (Scanner s = ReplicationTable.getScanner(client)) {
      WorkSection.limit(s);

      assertEquals(0, Iterables.size(s));
    }
  }

}
