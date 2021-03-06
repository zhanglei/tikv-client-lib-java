/*
 * Copyright 2017 PingCAP, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pingcap.tikv.catalog;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.pingcap.tikv.Snapshot;
import com.pingcap.tikv.meta.TiDBInfo;
import com.pingcap.tikv.meta.TiTableInfo;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class Catalog implements AutoCloseable {
  private Supplier<Snapshot> snapshotProvider;
  private ScheduledExecutorService service;
  private CatalogCache metaCache;

  @Override
  public void close() throws Exception {
    if(service != null) {
      service.shutdown();
    }
  }

  private static class CatalogCache {
    private CatalogCache(CatalogTransaction transaction) {
      this.transaction = transaction;
      this.dbCache = loadDatabases();
      this.tableCache = new ConcurrentHashMap<>();
      this.currentVersion = transaction.getLatestSchemaVersion();
    }

    private final Map<String, TiDBInfo> dbCache;
    private final ConcurrentHashMap<TiDBInfo, Map<String, TiTableInfo>> tableCache;
    private CatalogTransaction transaction;
    private long currentVersion;

    public CatalogTransaction getTransaction() {
      return transaction;
    }

    public long getVersion() {
      return currentVersion;
    }

    public TiDBInfo getDatabase(String name) {
      Objects.requireNonNull(name,"name is null");
      return dbCache.get(name.toLowerCase());
    }

    public List<TiDBInfo> listDatabases() {
      return ImmutableList.copyOf(dbCache.values());
    }

    public List<TiTableInfo> listTables(TiDBInfo db) {
      Map<String, TiTableInfo> tableMap = tableCache.get(db);
      if (tableMap == null) {
        tableMap = loadTables(db);
      }
      return ImmutableList.copyOf(tableMap.values());
    }

    public TiTableInfo getTable(TiDBInfo db, String tableName) {
      Map<String, TiTableInfo> tableMap = tableCache.get(db);
      if (tableMap == null) {
        tableMap = loadTables(db);
      }
      return tableMap.get(tableName.toLowerCase());
    }

    private Map<String, TiTableInfo> loadTables(TiDBInfo db) {
      List<TiTableInfo> tables = transaction.getTables(db.getId());
      ImmutableMap.Builder<String, TiTableInfo> builder = ImmutableMap.builder();
      for (TiTableInfo table : tables) {
        builder.put(table.getName(), table);
      }
      Map<String, TiTableInfo> tableMap = builder.build();
      tableCache.put(db, tableMap);
      return tableMap;
    }

    private Map<String, TiDBInfo> loadDatabases() {
      HashMap<String, TiDBInfo> newDBCache = new HashMap<>();

      List<TiDBInfo> databases = transaction.getDatabases();
      databases.forEach(db -> newDBCache.put(db.getName(), db));
      return newDBCache;
    }
  }

  public Catalog(Supplier<Snapshot> snapshotProvider, int refreshPeriod, TimeUnit periodUnit) {
    this.snapshotProvider = Objects.requireNonNull(snapshotProvider,
                                                   "Snapshot Provider is null");
    metaCache = new CatalogCache(new CatalogTransaction(snapshotProvider.get()));
    service = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder().setDaemon(true).build());
    service.scheduleAtFixedRate(this::reloadCache, refreshPeriod, refreshPeriod, periodUnit);
  }

  public Catalog(Supplier<Snapshot> snapshotProvider) {
    this.snapshotProvider = Objects.requireNonNull(snapshotProvider,
        "Snapshot Provider is null");
    metaCache = new CatalogCache(new CatalogTransaction(snapshotProvider.get()));
  }

  private void reloadCache() {
    Snapshot snapshot = snapshotProvider.get();
    CatalogTransaction newTrx = new CatalogTransaction(snapshot);
    long latestVersion = newTrx.getLatestSchemaVersion();
    if (latestVersion > metaCache.getVersion()) {
      metaCache = new CatalogCache(newTrx);
    }
  }

  public List<TiDBInfo> listDatabases() {
    return metaCache.listDatabases();
  }

  public List<TiTableInfo> listTables(TiDBInfo database) {
    Objects.requireNonNull(database, "database is null");
    return metaCache.listTables(database);
  }

  public TiDBInfo getDatabase(String dbName) {
    Objects.requireNonNull(dbName, "dbName is null");
    return metaCache.getDatabase(dbName);
  }

  public TiTableInfo getTable(String dbName, String tableName) {
    TiDBInfo database = getDatabase(dbName);
    if (database == null) {
      return null;
    }
    return getTable(database, tableName);
  }

  public TiTableInfo getTable(TiDBInfo database, String tableName) {
    Objects.requireNonNull(database, "database is null");
    Objects.requireNonNull(tableName, "tableName is null");
    return metaCache.getTable(database, tableName);
  }

  public TiTableInfo getTable(TiDBInfo database, long tableId) {
    Objects.requireNonNull(database, "database is null");
    Collection<TiTableInfo> tables = listTables(database);
    for (TiTableInfo table : tables) {
      if (table.getId() == tableId) {
        return table;
      }
    }
    return null;
  }
}
