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
package org.apache.accumulo.server.conf;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.clientImpl.Namespace;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.data.InstanceId;
import org.apache.accumulo.core.data.NamespaceId;
import org.apache.accumulo.fate.zookeeper.ZooCache;
import org.apache.accumulo.fate.zookeeper.ZooCacheFactory;
import org.apache.accumulo.fate.zookeeper.ZooUtil;
import org.apache.accumulo.server.MockServerContext;
import org.apache.accumulo.server.ServerContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class NamespaceConfigurationTest {
  private static final NamespaceId NSID = NamespaceId.of("namespace");
  private static final String ZOOKEEPERS = "localhost";
  private static final int ZK_SESSION_TIMEOUT = 120000;

  private InstanceId iid;
  private ServerContext context;
  private AccumuloConfiguration parent;
  private ZooCacheFactory zcf;
  private ZooCache zc;
  private NamespaceConfiguration c;

  @BeforeEach
  public void setUp() {
    iid = InstanceId.of(UUID.randomUUID());

    context = MockServerContext.getWithZK(iid, ZOOKEEPERS, ZK_SESSION_TIMEOUT);
    parent = createMock(AccumuloConfiguration.class);
    replay(context);

    c = new NamespaceConfiguration(NSID, context, parent);
    zcf = createMock(ZooCacheFactory.class);
    c.setZooCacheFactory(zcf);

    zc = createMock(ZooCache.class);
    expect(zcf.getZooCache(eq(ZOOKEEPERS), eq(ZK_SESSION_TIMEOUT))).andReturn(zc);
    replay(zcf);
  }

  @Test
  public void testGetters() {
    assertEquals(NSID, c.getNamespaceId());
    assertEquals(parent, c.getParentConfiguration());
  }

  @Test
  public void testGet_InZK() {
    Property p = Property.INSTANCE_SECRET;
    expect(zc.get(ZooUtil.getRoot(iid) + Constants.ZNAMESPACES + "/" + NSID
        + Constants.ZNAMESPACE_CONF + "/" + p.getKey())).andReturn("sekrit".getBytes(UTF_8));
    replay(zc);
    assertEquals("sekrit", c.get(Property.INSTANCE_SECRET));
  }

  @Test
  public void testGet_InParent() {
    Property p = Property.INSTANCE_SECRET;
    expect(zc.get(ZooUtil.getRoot(iid) + Constants.ZNAMESPACES + "/" + NSID
        + Constants.ZNAMESPACE_CONF + "/" + p.getKey())).andReturn(null);
    replay(zc);
    expect(parent.get(p)).andReturn("sekrit");
    replay(parent);
    assertEquals("sekrit", c.get(Property.INSTANCE_SECRET));
  }

  @Test
  public void testGet_SkipParentIfAccumuloNS() {
    c = new NamespaceConfiguration(Namespace.ACCUMULO.id(), context, parent);
    c.setZooCacheFactory(zcf);
    Property p = Property.INSTANCE_SECRET;
    expect(zc.get(ZooUtil.getRoot(iid) + Constants.ZNAMESPACES + "/" + Namespace.ACCUMULO.id()
        + Constants.ZNAMESPACE_CONF + "/" + p.getKey())).andReturn(null);
    replay(zc);
    assertNull(c.get(Property.INSTANCE_SECRET));
  }

  @Test
  public void testGetProperties() {
    Predicate<String> all = x -> true;
    Map<String,String> props = new java.util.HashMap<>();
    parent.getProperties(props, all);
    replay(parent);
    List<String> children = new java.util.ArrayList<>();
    children.add("foo");
    children.add("ding");
    expect(zc.getChildren(
        ZooUtil.getRoot(iid) + Constants.ZNAMESPACES + "/" + NSID + Constants.ZNAMESPACE_CONF))
            .andReturn(children);
    expect(zc.get(ZooUtil.getRoot(iid) + Constants.ZNAMESPACES + "/" + NSID
        + Constants.ZNAMESPACE_CONF + "/foo")).andReturn("bar".getBytes(UTF_8));
    expect(zc.get(ZooUtil.getRoot(iid) + Constants.ZNAMESPACES + "/" + NSID
        + Constants.ZNAMESPACE_CONF + "/ding")).andReturn("dong".getBytes(UTF_8));
    replay(zc);
    c.getProperties(props, all);
    assertEquals(2, props.size());
    assertEquals("bar", props.get("foo"));
    assertEquals("dong", props.get("ding"));
  }

  @Test
  public void testInvalidateCache() {
    // need to do a get so the accessor is created
    Property p = Property.INSTANCE_SECRET;
    expect(zc.get(ZooUtil.getRoot(iid) + Constants.ZNAMESPACES + "/" + NSID
        + Constants.ZNAMESPACE_CONF + "/" + p.getKey())).andReturn("sekrit".getBytes(UTF_8));
    zc.clear();
    replay(zc);
    c.get(Property.INSTANCE_SECRET);
    c.invalidateCache();
    verify(zc);
  }
}
