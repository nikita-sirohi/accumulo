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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.BatchWriter;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Mutation;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.harness.AccumuloClusterHarness;
import org.apache.hadoop.io.Text;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Iterators;

public class DeleteRowsIT extends AccumuloClusterHarness {

  private static final Logger log = LoggerFactory.getLogger(DeleteRowsIT.class);

  private static final int ROWS_PER_TABLET = 10;
  private static final List<String> LETTERS = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i",
      "j", "k", "l", "m", "n", "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z");
  static final TreeSet<Text> SPLITS =
      LETTERS.stream().map(Text::new).collect(Collectors.toCollection(TreeSet::new));

  static final List<String> ROWS = new ArrayList<>(LETTERS);
  // put data on first and last tablet
  static {
    ROWS.add("A");
    ROWS.add("{");
  }

  @Override
  protected Duration defaultTimeout() {
    return Duration.ofMinutes(5);
  }

  @Test
  public void testDeleteAllRows() throws Exception {
    try (AccumuloClient c = Accumulo.newClient().from(getClientProps()).build()) {
      String[] tableNames = this.getUniqueNames(20);
      for (String tableName : tableNames) {
        c.tableOperations().create(tableName);
        c.tableOperations().deleteRows(tableName, null, null);
        try (Scanner scanner = c.createScanner(tableName, Authorizations.EMPTY)) {
          assertEquals(0, Iterators.size(scanner.iterator()));
        }
      }
    }
  }

  @Test
  public void testManyRows() throws Exception {
    try (AccumuloClient c = Accumulo.newClient().from(getClientProps()).build()) {
      // Delete ranges of rows, and verify the tablets are removed.
      int i = 0;
      // Eliminate whole tablets
      String tableName = getUniqueNames(1)[0];
      testSplit(c, tableName + i++, "f", "h", "abcdefijklmnopqrstuvwxyz", 260);
      // Eliminate whole tablets, partial first tablet
      testSplit(c, tableName + i++, "f1", "h", "abcdeff1ijklmnopqrstuvwxyz", 262);
      // Eliminate whole tablets, partial last tablet
      testSplit(c, tableName + i++, "f", "h1", "abcdefijklmnopqrstuvwxyz", 258);
      // Eliminate whole tablets, partial first and last tablet
      testSplit(c, tableName + i++, "f1", "h1", "abcdeff1ijklmnopqrstuvwxyz", 260);
      // Eliminate one tablet
      testSplit(c, tableName + i++, "f", "g", "abcdefhijklmnopqrstuvwxyz", 270);
      // Eliminate partial tablet, matches start split
      testSplit(c, tableName + i++, "f", "f1", "abcdefghijklmnopqrstuvwxyz", 278);
      // Eliminate partial tablet, matches end split
      testSplit(c, tableName + i++, "f1", "g", "abcdeff1hijklmnopqrstuvwxyz", 272);
      // Eliminate tablets starting at -inf
      testSplit(c, tableName + i++, null, "h", "ijklmnopqrstuvwxyz", 200);
      // Eliminate tablets ending at +inf
      testSplit(c, tableName + i++, "t", null, "abcdefghijklmnopqrst", 200);
      // Eliminate some rows inside one tablet
      testSplit(c, tableName + i++, "t0", "t2", "abcdefghijklmnopqrstt0uvwxyz", 278);
      // Eliminate some rows in the first tablet
      testSplit(c, tableName + i++, null, "A1", "abcdefghijklmnopqrstuvwxyz", 278);
      // Eliminate some rows in the last tablet
      testSplit(c, tableName + i++, "{1", null, "abcdefghijklmnopqrstuvwxyz{1", 272);
      // Delete everything
      testSplit(c, tableName + i++, null, null, "", 0);
    }
  }

  private void testSplit(AccumuloClient c, String table, String start, String end, String result,
      int entries) throws Exception {
    // Put a bunch of rows on each tablet
    c.tableOperations().create(table);
    try (BatchWriter bw = c.createBatchWriter(table)) {
      for (String row : ROWS) {
        for (int j = 0; j < ROWS_PER_TABLET; j++) {
          Mutation m = new Mutation(row + j);
          m.put("cf", "cq", "value");
          bw.addMutation(m);
        }
      }
      bw.flush();
    }
    // Split the table
    c.tableOperations().addSplits(table, SPLITS);

    Text startText = start == null ? null : new Text(start);
    Text endText = end == null ? null : new Text(end);
    c.tableOperations().deleteRows(table, startText, endText);
    Collection<Text> remainingSplits = c.tableOperations().listSplits(table);
    StringBuilder sb = new StringBuilder();
    // See that whole tablets are removed
    for (Text split : remainingSplits)
      sb.append(split);
    assertEquals(result, sb.toString());
    // See that the rows are really deleted
    try (Scanner scanner = c.createScanner(table, Authorizations.EMPTY)) {
      int count = 0;
      for (Entry<Key,Value> entry : scanner) {
        Text row = entry.getKey().getRow();
        assertTrue((startText == null || row.compareTo(startText) <= 0)
            || (endText == null || row.compareTo(endText) > 0));
        assertTrue(startText != null || endText != null);
        count++;
      }
      log.info("Finished table {}", table);
      assertEquals(entries, count);
    }
  }

}
