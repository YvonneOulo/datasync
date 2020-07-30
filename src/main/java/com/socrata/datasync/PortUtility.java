package com.socrata.datasync;

import com.socrata.api.DatasetDestination;
import com.socrata.api.HttpLowLevel;
import com.socrata.api.Soda2Consumer;
import com.socrata.api.Soda2Producer;
import com.socrata.api.SodaDdl;
import com.socrata.builders.SoqlQueryBuilder;
import com.socrata.datasync.job.JobStatus;
import com.socrata.exceptions.LongRunningQueryException;
import com.socrata.exceptions.SodaError;
import com.socrata.model.UpsertResult;
import com.socrata.model.importer.Column;
import com.socrata.model.importer.Dataset;
import com.socrata.model.importer.DatasetInfo;
import com.socrata.model.soql.SoqlQuery;
import javax.ws.rs.core.Response;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class PortUtility {

    private static final String groupingKey = "grouping_aggregate";
    private static final String drillingKey = "drill_down";

    private PortUtility() {
        throw new AssertionError("Never instantiate utility classes!");
    }

    public static String portSchema(SodaDdl loader, SodaDdl creator,
                                    final String sourceSetID, final String destinationDatasetTitle,
                                    boolean actuallyCopySchema)
        throws SodaError, InterruptedException
    {
        System.out.print("Copying schema from dataset " + sourceSetID);
        Dataset sourceSet = (Dataset) loader.loadDatasetInfo(sourceSetID);

        if(destinationDatasetTitle != null && !destinationDatasetTitle.equals(""))
            sourceSet.setName(destinationDatasetTitle);

        DatasetDestination destination =
            sourceSet.isNewBackend() ? DatasetDestination.NBE
                                     : DatasetDestination.OBE;

        String sourceResourceName = sourceSet.getResourceName();
        if (sourceResourceName != null) {
            Boolean isSameDomain = loader.getHttpLowLevel().uriBuilder().build().equals(creator.getHttpLowLevel().uriBuilder().build());
            if (isSameDomain) {
                // Resource name is unique in the same domain.  Hence do not copy resource name.
                sourceSet.setResourceName(null);
            }
        }

        if(actuallyCopySchema) {
            adaptSchemaForAggregates(sourceSet);
        } else {
            sourceSet.setColumns(Collections.<Column>emptyList());
        }

        DatasetInfo sinkSet = creator.createDataset(sourceSet, destination);

        if (sourceResourceName != null && sourceSet.getResourceName() == null) {
            sourceSet.setResourceName(sourceResourceName);
        }

        String sinkSetID = sinkSet.getId();
        System.out.println(" to dataset " + sinkSetID);
        return sinkSetID;
    }

    public static String publishDataset(SodaDdl publisher, String sinkSetID)
        throws SodaError, InterruptedException
    {
        DatasetInfo publishedSet = publisher.publish(sinkSetID);
        String publishedID = publishedSet.getId();
        return publishedID;
    }

    public static void portContents(Soda2Consumer streamExporter, Soda2Producer streamUpserter, String sourceSetID,
                                    String sinkSetID, PublishMethod publishMethod)
        throws InterruptedException, LongRunningQueryException, SodaError, IOException
    {
        switch (publishMethod) {
        case upsert:
            upsertContents(streamExporter, streamUpserter, sourceSetID, sinkSetID);
            break;
        case replace:
            replaceContents(streamExporter, streamUpserter, sourceSetID, sinkSetID);
        }
    }

    private static void upsertContents(Soda2Consumer streamExporter, Soda2Producer streamUpserter,
                                       String sourceSetID, String sinkSetID)
        throws InterruptedException, LongRunningQueryException, SodaError, IOException
    {
        System.out.println("Upserting contents of dataset " + sourceSetID + " into dataset " + sinkSetID);

        int retryLimit = 10;
        int retryPause = 10000; // sleep 10 seconds before retrying on errors

        // Limit of 1000 rows per export, so page through dataset using $offset
        int offset = 0;
        int rowsUpserted = 0;
        Response response;
        ObjectMapper mapper = new ObjectMapper();
        List<Map<String, Object>> rowSet;

        do {
            SoqlQuery myQuery =new SoqlQueryBuilder().setOffset(offset).build();
            for(int retries = 0;; ++retries) {
                try {
                    response = streamExporter.query(sourceSetID, HttpLowLevel.JSON_TYPE, myQuery);
                    rowSet = mapper.readValue(response.readEntity(InputStream.class), new TypeReference<List<Map<String,Object>>>() {});
                    break;
                } catch(SodaError|IOException e) {
                    if(retries == retryLimit) throw e;
                    System.out.println("Caught an error, waiting a bit then retrying.  Error was: " + e.getMessage());
                    Thread.sleep(retryPause);
                }
            }
            if (rowSet.size() > 0) {
                offset += rowSet.size();
                UpsertResult result;
                for(int retries = 0;; ++retries) {
                    try {
                        result = streamUpserter.upsert(sinkSetID, rowSet);
                        break;
                    } catch(SodaError e) {
                        if(retries == retryLimit) throw e;
                        System.out.println("Caught an error, waiting a bit then retrying.  Error was: " + e.getMessage());
                        Thread.sleep(retryPause);
                    }
                }
                rowsUpserted += result.getRowsCreated() + result.getRowsUpdated();
                System.out.println("\tUpserted " + rowsUpserted + " rows.");
            }
        } while (rowSet.size() > 0);
    }

    private static void replaceContents(Soda2Consumer streamExporter, Soda2Producer streamUpserter,
                                        String sourceSetID, String sinkSetID)
        throws InterruptedException, LongRunningQueryException, SodaError, IOException
    {
        System.out.println("Replacing contents of dataset " + sourceSetID + " into dataset " + sinkSetID);
        // Limit of 1000 rows per export, so page through dataset using $offset
        int offset = 0;
        int batchesRead = 0;
        SoqlQuery myQuery;
        Response response;
        ObjectMapper mapper = new ObjectMapper().configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false);
        List<Map<String, Object>> rowSet;
        final File tempFile = File.createTempFile("replacement_dataset", ".json");
        tempFile.createNewFile();
        tempFile.deleteOnExit();
        try (FileWriter tempOut = new FileWriter(tempFile, true)) {

            tempOut.write("[\n");
            do {
                myQuery = new SoqlQueryBuilder().setOffset(offset).build();
                response = streamExporter.query(sourceSetID, HttpLowLevel.JSON_TYPE, myQuery);
                rowSet = mapper.readValue(response.readEntity(InputStream.class),
                                          new TypeReference<List<Map<String, Object>>>() {
                                          }
                                          );
                if (batchesRead > 0 && rowSet.size() > 0)
                    tempOut.write(",\n");
                for (int i = 0; i < rowSet.size(); i++) {
                    mapper.writeValue(tempOut, rowSet.get(i));
                    if (i != rowSet.size() - 1)
                        tempOut.write(",\n");
                }
                offset += rowSet.size();
                batchesRead += 1;
                System.out.println("\tGathered " + Utils.ordinal(batchesRead) + " batch of 1000 rows for replacement");
                response.close();
            } while (rowSet.size() > 0);
            tempOut.write("\n]");
        }

        System.out.print("\tReplacing data . . .");
        FileInputStream replacementFile = new FileInputStream(tempFile);
        streamUpserter.replaceStream(sinkSetID, HttpLowLevel.JSON_TYPE, replacementFile);
        System.out.println();
    }

    public static JobStatus assertSchemasAreAlike(SodaDdl sourceChecker, SodaDdl sinkChecker, String sourceSetID, String sinkSetID)
        throws SodaError, InterruptedException
    {
        // We don't need to test metadata; we're only concerned with the columns...
        Dataset sourceSchema = (Dataset) sourceChecker.loadDatasetInfo(sourceSetID);
        Dataset sinkSchema = (Dataset) sinkChecker.loadDatasetInfo(sinkSetID);
        // Grab the columns...
        List<Column> sourceColumns = sourceSchema.getColumns();
        List<Column> sinkColumns = sinkSchema.getColumns();
        // And let the tests begin.
        if(sourceColumns.size() == sinkColumns.size()) {
            // If the sizes are the same we can begin comparing columns
            for (int i = 0; i < sourceColumns.size(); i++) {
                // The aspects of the columns that we care about are the API field names and their data types
                if(!sourceColumns.get(i).getFieldName().equals(sinkColumns.get(i).getFieldName()) ||
                   !sourceColumns.get(i).getDataTypeName().equals(sinkColumns.get(i).getDataTypeName())){
                    return JobStatus.INVALID_SCHEMAS;
                }
            }
        } else {
            return JobStatus.INVALID_SCHEMAS;
        }
        return JobStatus.SUCCESS;
    }

    /**
     * Changes the columnar information in the given dataset to remove the "grouping_aggregate" field from "format",
     * when present, and prepend its value to the column field name.  Removal of the field is necessary to
     * successfully upload the schema to core (which would otherwise throw an error about refusing to create a column
     * with a grouping_aggregrate but no group-by).  The editing of the field name is necessary for subsequent
     * data loading, since the data from soda2 expectst aggregated columns to include the grouping_aggregate.
     * Also removes drill-down formatting info, as this is non-sensical without the unaggregated data
     * Also removing the resourceName - no port job can succeed with one present.
     * @param schema the Dataset from soda-java representing the schema
     */
    public static void adaptSchemaForAggregates(Dataset schema) {
        // TODO: give users the option to choose a new resource name; in the meanwhile, it can be set after the job completes
        schema.setResourceName(null);
        List<Column> columns = schema.getColumns();
        for (int i = 0; i < columns.size(); i++) {
            Column col = columns.get(i);
            if (col != null) {
                Map<String, String> format = col.getFormat();
                if (format != null) {
                    String aggregation = format.remove(groupingKey);
                    format.remove(drillingKey);
                    if (aggregation != null) {
                        String oldFieldName = col.getFieldName();
                        col.setFieldName(aggregation + "_" + oldFieldName);
                    }
                }
            }
        }
    }
}
