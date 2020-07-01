/*
 * Copyright 2020 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.mantisrx.connector.iceberg.sink.writer;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.mantisrx.connector.iceberg.sink.codecs.IcebergCodecs;
import io.mantisrx.connector.iceberg.sink.config.SinkProperties;
import io.mantisrx.connector.iceberg.sink.writer.config.WriterConfig;
import io.mantisrx.connector.iceberg.sink.writer.config.WriterProperties;
import io.mantisrx.connector.iceberg.sink.writer.metrics.WriterMetrics;
import io.mantisrx.runtime.Context;
import io.mantisrx.runtime.ScalarToScalar;
import io.mantisrx.runtime.WorkerInfo;
import io.mantisrx.runtime.computation.ScalarComputation;
import io.mantisrx.runtime.parameter.ParameterDefinition;
import io.mantisrx.runtime.parameter.type.IntParameter;
import io.mantisrx.runtime.parameter.type.StringParameter;
import io.mantisrx.runtime.parameter.validator.Validators;
import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.Table;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.iceberg.data.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import rx.Observable;
import rx.exceptions.Exceptions;

/**
 * Processing stage which writes records to Iceberg through a backing file store.
 */
public class IcebergWriterStage implements ScalarComputation<Record, DataFile> {

    private static final Logger logger = LoggerFactory.getLogger(IcebergWriterStage.class);

    private Transformer transformer;

    /**
     * Returns a config for this stage which has encoding/decoding semantics and parameter definitions.
     */
    public static ScalarToScalar.Config<Record, DataFile> config() {
        return new ScalarToScalar.Config<Record, DataFile>()
                .description("")
                .codec(IcebergCodecs.dataFile())
                .withParameters(parameters());
    }

    /**
     * Returns a list of parameter definitions for this stage.
     */
    public static List<ParameterDefinition<?>> parameters() {
        return Arrays.asList(
                new StringParameter().name(SinkProperties.SINK_CATALOG)
                        .description(SinkProperties.SINK_CATALOG_DESCRIPTION)
                        .validator(Validators.notNullOrEmpty())
                        .required()
                        .build(),
                new StringParameter().name(SinkProperties.SINK_DATABASE)
                        .description(SinkProperties.SINK_DATABASE_DESCRIPTION)
                        .validator(Validators.notNullOrEmpty())
                        .required()
                        .build(),
                new StringParameter().name(SinkProperties.SINK_TABLE)
                        .description(SinkProperties.SINK_TABLE_DESCRIPTION)
                        .validator(Validators.notNullOrEmpty())
                        .required()
                        .build(),
                new IntParameter().name(WriterProperties.WRITER_ROW_GROUP_SIZE)
                        .description(WriterProperties.WRITER_ROW_GROUP_SIZE_DESCRIPTION)
                        .validator(Validators.alwaysPass())
                        .defaultValue(WriterProperties.WRITER_ROW_GROUP_SIZE_DEFAULT)
                        .build(),
                new StringParameter().name(WriterProperties.WRITER_FLUSH_FREQUENCY_BYTES)
                        .description(WriterProperties.WRITER_FLUSH_FREQUENCY_BYTES_DESCRIPTION)
                        .validator(Validators.alwaysPass())
                        .defaultValue(WriterProperties.WRITER_FLUSH_FREQUENCY_BYTES_DEFAULT)
                        .build(),
                new StringParameter().name(WriterProperties.WRITER_FILE_FORMAT)
                        .description(WriterProperties.WRITER_FILE_FORMAT_DESCRIPTION)
                        .validator(Validators.alwaysPass())
                        .defaultValue(WriterProperties.WRITER_FILE_FORMAT_DEFAULT)
                        .build()
        );
    }

    /**
     * Use this method to create an Iceberg Writer independent of a Mantis Processing Stage.
     * <p>
     * This is useful for optimizing network utilization by using the writer directly within another
     * processing stage instead of having to traverse stage boundaries.
     * <p>
     * This incurs a debuggability trade-off where a processing stage will do multiple things.
     */
    public static IcebergWriter newIcebergWriter(WriterConfig config, WorkerInfo workerInfo, Table table) {
        if (table.spec().fields().isEmpty()) {
            return new UnpartitionedIcebergWriter(config, workerInfo, table);
        } else {
            return new PartitionedIcebergWriter(config, workerInfo, table);
        }
    }

    public IcebergWriterStage() {
    }

    /**
     * Uses the provided Mantis Context to inject configuration and opens an underlying file appender.
     * <p>
     * This method depends on a Hadoop Configuration and Iceberg Catalog, both injected
     * from the Context's service locator.
     * <p>
     * Note that this method expects an Iceberg Table to have been previously created out-of-band,
     * otherwise initialization will fail. Users should prefer to create tables
     * out-of-band so they can be versioned alongside their schemas.
     */
    @Override
    public void init(Context context) {
        Configuration hadoopConfig = context.getServiceLocator().service(Configuration.class);
        WriterConfig config = new WriterConfig(context.getParameters(), hadoopConfig);
        Catalog catalog = context.getServiceLocator().service(Catalog.class);
        // TODO: Get namespace and name from config.
        TableIdentifier id = TableIdentifier.of(config.getCatalog(), config.getDatabase(), config.getTable());
        Table table = catalog.loadTable(id);
        WorkerInfo workerInfo = context.getWorkerInfo();

        IcebergWriter writer = newIcebergWriter(config, workerInfo, table);
        WriterMetrics metrics = new WriterMetrics();
        transformer = new Transformer(config, metrics, writer);
    }

    @Override
    public Observable<DataFile> call(Context context, Observable<Record> recordObservable) {
        return recordObservable.compose(transformer);
    }

    /**
     * Reactive Transformer for writing records to Iceberg.
     * <p>
     * Users may use this class independently of this Stage, for example, if they want to
     * {@link Observable#compose(Observable.Transformer)} this transformer with a flow into
     * an existing Stage. One benefit of this co-location is to avoid extra network
     * cost from worker-to-worker communication, trading off debuggability.
     */
    public static class Transformer implements Observable.Transformer<Record, DataFile> {

        private static final DataFile ERROR_DATA_FILE = new DataFiles.Builder()
                .withPath("/error.parquet")
                .withFileSizeInBytes(0L)
                .withRecordCount(0L)
                .build();

        private final WriterConfig config;
        private final WriterMetrics metrics;
        private final IcebergWriter writer;

        public Transformer(WriterConfig config, WriterMetrics metrics, IcebergWriter writer) {
            this.config = config;
            this.metrics = metrics;
            this.writer = writer;
        }

        /**
         * Opens an IcebergWriter FileAppender, writes records to a file. Check the file appender
         * size on a configured count, and if over a configured threshold, close the file, build
         * and emit a DataFile, and open a new FileAppender.
         * <p>
         * Pair this with a progressive multipart file uploader backend for better latencies.
         */
        @Override
        public Observable<DataFile> call(Observable<Record> source) {
            return source
                    .doOnNext(record -> {
                        if (writer.isClosed()) {
                            try {
                                writer.open();
                                metrics.increment(WriterMetrics.OPEN_SUCCESS_COUNT);
                            } catch (IOException e) {
                                metrics.increment(WriterMetrics.OPEN_FAILURE_COUNT);
                                throw Exceptions.propagate(e);
                            }
                        }
                    })
                    .scan(new Counter(config.getWriterRowGroupSize()), (counter, record) -> {
                        try {
                            writer.write(record);
                            counter.increment();
                            metrics.increment(WriterMetrics.WRITE_SUCCESS_COUNT);
                        } catch (RuntimeException e) {
                            metrics.increment(WriterMetrics.WRITE_FAILURE_COUNT);
                            logger.error("error writing record {}", record);
                        }
                        return counter;
                    })
                    .filter(Counter::shouldReset)
                    .filter(counter -> writer.length() >= config.getWriterFlushFrequencyBytes())
                    .map(counter -> {
                        try {
                            DataFile dataFile = writer.close();
                            counter.reset();
                            return dataFile;
                        } catch (IOException e) {
                            metrics.increment(WriterMetrics.BATCH_FAILURE_COUNT);
                            logger.error("error writing DataFile", e);
                            return ERROR_DATA_FILE;
                        }
                    })
                    .filter(dataFile -> !isErrorDataFile(dataFile))
                    .doOnNext(dataFile -> {
                        metrics.increment(WriterMetrics.BATCH_SUCCESS_COUNT);
                        logger.info("writing DataFile: {}", dataFile);
                        metrics.setGauge(WriterMetrics.BATCH_SIZE, dataFile.recordCount());
                        metrics.setGauge(WriterMetrics.BATCH_SIZE_BYTES, dataFile.fileSizeInBytes());
                    })
                    .doOnSubscribe(() -> {
                        try {
                            writer.open();
                            metrics.increment(WriterMetrics.OPEN_SUCCESS_COUNT);
                        } catch (IOException e) {
                            metrics.increment(WriterMetrics.OPEN_FAILURE_COUNT);
                            throw Exceptions.propagate(e);
                        }
                    })
                    .doOnTerminate(() -> {
                        if (!writer.isClosed()) {
                            try {
                                logger.info("closing writer on rx terminate signal");
                                writer.close();
                            } catch (IOException e) {
                                throw Exceptions.propagate(e);
                            }
                        }
                    });
        }

        private boolean isErrorDataFile(DataFile dataFile) {
            return ERROR_DATA_FILE.path() == dataFile.path() &&
                    ERROR_DATA_FILE.fileSizeInBytes() == dataFile.fileSizeInBytes() &&
                    ERROR_DATA_FILE.recordCount() == dataFile.recordCount();
        }

        private static class Counter {

            private final int threshold;
            private int counter;

            Counter(int threshold) {
                this.threshold = threshold;
                this.counter = 0;
            }

            void increment() {
                counter++;
            }

            void reset() {
                counter = 0;
            }

            boolean shouldReset() {
                return counter >= threshold;
            }
        }
    }
}
