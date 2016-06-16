/*
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
package com.teradata.tempto.internal.fulfillment.table.hive;

import com.google.inject.Inject;
import com.teradata.tempto.fulfillment.table.MutableTableRequirement.State;
import com.teradata.tempto.fulfillment.table.TableDefinition;
import com.teradata.tempto.fulfillment.table.TableHandle;
import com.teradata.tempto.fulfillment.table.TableManager;
import com.teradata.tempto.fulfillment.table.hive.HiveDataSource;
import com.teradata.tempto.fulfillment.table.hive.HiveTableDefinition;
import com.teradata.tempto.internal.fulfillment.table.AbstractTableManager;
import com.teradata.tempto.internal.fulfillment.table.TableName;
import com.teradata.tempto.internal.fulfillment.table.TableNameGenerator;
import com.teradata.tempto.internal.hadoop.hdfs.HdfsDataSourceWriter;
import com.teradata.tempto.query.QueryExecutor;
import org.slf4j.Logger;

import javax.inject.Named;
import javax.inject.Singleton;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static com.teradata.tempto.fulfillment.table.MutableTableRequirement.State.LOADED;
import static com.teradata.tempto.fulfillment.table.MutableTableRequirement.State.PREPARED;
import static java.lang.String.format;
import static org.slf4j.LoggerFactory.getLogger;

@TableManager.Descriptor(tableDefinitionClass = HiveTableDefinition.class, type = "HIVE")
@Singleton
public class HiveTableManager
        extends AbstractTableManager<HiveTableDefinition>
{
    private static final Logger LOGGER = getLogger(HiveTableManager.class);

    private final QueryExecutor queryExecutor;
    private final HdfsDataSourceWriter hdfsDataSourceWriter;
    private final String testDataBasePath;
    private final String databaseName;

    @Inject
    public HiveTableManager(QueryExecutor queryExecutor,
            HdfsDataSourceWriter hdfsDataSourceWriter,
            TableNameGenerator tableNameGenerator,
            @Named("tests.hdfs.path") String testDataBasePath,
            @Named("databaseName") String databaseName)
    {
        super(queryExecutor, tableNameGenerator);
        this.databaseName = databaseName;
        this.queryExecutor = checkNotNull(queryExecutor, "queryExecutor is null");
        this.hdfsDataSourceWriter = checkNotNull(hdfsDataSourceWriter, "hdfsDataSourceWriter is null");
        this.testDataBasePath = checkNotNull(testDataBasePath, "testDataBasePath is null");
    }

    @Override
    public HiveTableInstance createImmutable(HiveTableDefinition tableDefinition, TableHandle tableHandle)
    {
        checkState(!tableDefinition.isPartitioned(), "Partitioning not supported for immutable tables");
        TableName tableName = createImmutableTableName(tableHandle);
        String tableNameInDatabase = tableHandle.getName();
        LOGGER.debug("creating immutable table {}", tableNameInDatabase);

        String tableDataPath = getImmutableTableHdfsPath(tableDefinition.getDataSource());
        uploadTableData(tableDataPath, tableDefinition.getDataSource());

        dropTableIgnoreError(tableName);
        createTable(tableDefinition, tableName, Optional.of(tableDataPath));
        markTableAsExternal(tableNameInDatabase);

        return new HiveTableInstance(tableName, tableDefinition);
    }

    @Override
    public HiveTableInstance createMutable(HiveTableDefinition tableDefinition, State state, TableHandle tableHandle)
    {
        TableName tableName = createMutableTableName(tableHandle);
        LOGGER.debug("creating mutable table {}", tableName);

        if (state == PREPARED) {
            return new HiveTableInstance(tableName, tableDefinition);
        }

        createTable(tableDefinition, tableName, Optional.empty());

        if (tableDefinition.isPartitioned()) {
            int partitionId = 0;
            for (HiveTableDefinition.PartitionDefinition partitionDefinition : tableDefinition.getPartitionDefinitons()) {
                String partitionDataPath = getMutableTableHdfsPath(tableName, Optional.of(partitionId));
                if (state == LOADED) {
                    uploadTableData(partitionDataPath, partitionDefinition.getDataSource());
                }
                queryExecutor.executeQuery(partitionDefinition.getAddPartitionTableDDL(tableName, partitionDataPath));
                partitionId++;
            }
        }
        else if (state == LOADED) {
            String tableDataPath = getMutableTableHdfsPath(tableName, Optional.empty());
            uploadTableData(tableDataPath, tableDefinition.getDataSource());
        }

        return new HiveTableInstance(tableName, tableDefinition);
    }

    @Override
    public String getDatabaseName()
    {
        return databaseName;
    }

    @Override
    public Class<? extends TableDefinition> getTableDefinitionClass()
    {
        return HiveTableDefinition.class;
    }

    private void uploadTableData(String tableDataPath, HiveDataSource dataSource)
    {
        hdfsDataSourceWriter.ensureDataOnHdfs(tableDataPath, dataSource);
    }

    private String getImmutableTableHdfsPath(HiveDataSource dataSource)
    {
        return testDataBasePath + "/" + dataSource.getPathSuffix();
    }

    private String getMutableTableHdfsPath(TableName tableName, Optional<Integer> partitionId)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("/user/hive/warehouse/");
        sb.append(tableName.getNameInDatabase());
        if (partitionId.isPresent()) {
            sb.append("/partition_").append(partitionId.get());
        }
        return sb.toString();
    }

    private void createTable(HiveTableDefinition tableDefinition, TableName tableName, Optional<String> tableDataPath)
    {
        tableName.getSchema().ifPresent(schema ->
                queryExecutor.executeQuery("CREATE SCHEMA IF NOT EXISTS " + schema)
        );
        queryExecutor.executeQuery(tableDefinition.getCreateTableDDL(tableName.getNameInDatabase(), tableDataPath));
    }

    private void markTableAsExternal(String tableNameInDatabase)
    {
        queryExecutor.executeQuery(format("ALTER TABLE %s SET TBLPROPERTIES('EXTERNAL'='TRUE')", tableNameInDatabase));
    }
}
