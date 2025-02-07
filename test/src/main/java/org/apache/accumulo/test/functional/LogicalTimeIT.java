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
package org.apache.accumulo.test.functional;

import java.time.Duration;
import java.util.TreeSet;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.NewTableConfiguration;
import org.apache.accumulo.core.client.admin.TimeType;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.harness.AccumuloClusterHarness;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LogicalTimeIT extends AccumuloClusterHarness {
  private static final Logger log = LoggerFactory.getLogger(LogicalTimeIT.class);

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(4);
  }

  @Test
  public void run() throws Exception {
    int tc = 0;
    String tableName = getUniqueNames(1)[0];
    try (AccumuloClient c = Accumulo.newClient().from(getClientProps()).build()) {
      runMergeTest(c, tableName + tc++, new String[] {"m"}, new String[] {"a"}, null, null, "b",
          2L);
      runMergeTest(c, tableName + tc++, new String[] {"m"}, new String[] {"z"}, null, null, "b",
          2L);
      runMergeTest(c, tableName + tc++, new String[] {"m"}, new String[] {"a", "z"}, null, null,
          "b", 2L);
      runMergeTest(c, tableName + tc++, new String[] {"m"}, new String[] {"a", "c", "z"}, null,
          null, "b", 3L);
      runMergeTest(c, tableName + tc++, new String[] {"m"}, new String[] {"a", "y", "z"}, null,
          null, "b", 3L);

      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"a"}, null, null,
          "b", 2L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"h"}, null, null,
          "b", 2L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"s"}, null, null,
          "b", 2L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"a", "h", "s"}, null,
          null, "b", 2L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"a", "c", "h", "s"},
          null, null, "b", 3L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"a", "h", "s", "i"},
          null, null, "b", 3L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"t", "a", "h", "s"},
          null, null, "b", 3L);

      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"a"}, null, "h", "b",
          2L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"h"}, null, "h", "b",
          2L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"s"}, null, "h", "b",
          1L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"a", "h", "s"}, null,
          "h", "b", 2L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"a", "c", "h", "s"},
          null, "h", "b", 3L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"a", "h", "s", "i"},
          null, "h", "b", 3L);
      runMergeTest(c, tableName + tc++, new String[] {"g", "r"}, new String[] {"t", "a", "h", "s"},
          null, "h", "b", 2L);
    }
  }

  private void runMergeTest(AccumuloClient client, String table, String[] splits, String[] inserts,
      String start, String end, String last, long expected) throws Exception {
    log.info("table {}", table);
    TreeSet<Text> splitSet = new TreeSet<>();
    for (String split : splits) {
      splitSet.add(new Text(split));
    }
    client.tableOperations().create(table,
        new NewTableConfiguration().setTimeType(TimeType.LOGICAL).withSplits(splitSet));
    BatchWriter bw = client.createBatchWriter(table);
    for (String row : inserts) {
      Mutation m = new Mutation(row);
      m.put("cf", "cq", "v");
      bw.addMutation(m);
    }

    bw.flush();

    client.tableOperations().merge(table, start == null ? null : new Text(start),
        end == null ? null : new Text(end));

    Mutation m = new Mutation(last);
    m.put("cf", "cq", "v");
    bw.addMutation(m);
    bw.flush();

    try (Scanner scanner = client.createScanner(table, Authorizations.EMPTY)) {
      scanner.setRange(new Range(last));

      bw.close();

      long time = scanner.iterator().next().getKey().getTimestamp();
      if (time != expected) {
        throw new RuntimeException("unexpected time " + time + " " + expected);
      }
    }
  }

}
